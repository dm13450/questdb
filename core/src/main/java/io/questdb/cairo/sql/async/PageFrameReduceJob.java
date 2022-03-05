/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2022 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.sql.async;

import io.questdb.MessageBus;
import io.questdb.cairo.sql.PageAddressCacheRecord;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.Job;
import io.questdb.mp.MCSequence;
import io.questdb.mp.RingQueue;
import io.questdb.std.Rnd;

import java.util.concurrent.atomic.AtomicInteger;

public class PageFrameReduceJob implements Job {

    private final static Log LOG = LogFactory.getLog(PageFrameReduceJob.class);
    private final PageAddressCacheRecord[] record;
    private final int[] shards;
    private final int shardCount;
    private final MessageBus messageBus;

    // Each thread should be assigned own instance of this job, making the code effectively
    // single threaded. Such assignment is necessary for threads to have their own shard walk sequence.
    public PageFrameReduceJob(MessageBus bus, Rnd rnd, int workerCount) {
        this.messageBus = bus;
        this.shardCount = messageBus.getPageFrameReduceShardCount();
        this.shards = new int[shardCount];
        // fill shards[] with shard indexes
        for (int i = 0; i < shardCount; i++) {
            shards[i] = i;
        }

        // shuffle shard indexes such that each job has its own
        // pass order over the shared queues
        int currentIndex = shardCount;
        int randomIndex;
        while (currentIndex != 0) {
            randomIndex = (int) Math.floor(rnd.nextDouble() * currentIndex);
            currentIndex--;

            final int tmp = shards[currentIndex];
            shards[currentIndex] = shards[randomIndex];
            shards[randomIndex] = tmp;
        }

        this.record = new PageAddressCacheRecord[workerCount];
        for (int i = 0; i < workerCount; i++) {
            this.record[i] = new PageAddressCacheRecord();
        }
    }

    /**
     * Reduces single queue item when item is available. Return value is inverted as in
     * true when queue item is not available, false otherwise. Item is reduced using the
     * reducer method provided with each queue item.
     *
     * @param queue  page frame queue instance
     * @param subSeq subscriber sequence
     * @param record instance of record that can be positioned on the frame and each row in that frame
     * @return inverted value of queue processing status; true if nothing was processed.
     */
    public static boolean consumeQueue(
            RingQueue<PageFrameReduceTask> queue,
            MCSequence subSeq,
            PageAddressCacheRecord record
    ) {
        // loop is required to deal with CAS errors, cursor == -2
        do {
            final long cursor = subSeq.next();
            if (cursor > -1) {
                final PageFrameReduceTask task = queue.get(cursor);
                final PageFrameSequence<?> frameSequence = task.getFrameSequence();
                try {
                    final AtomicInteger framesReducedCounter = frameSequence.getReduceCounter();
                    try {
                        LOG.debug()
                                .$("reducing [shard=").$(frameSequence.getShard())
                                .$(", id=").$(frameSequence.getId())
                                .$(", frameIndex=").$(task.getFrameIndex())
                                .$(", frameCount=").$(frameSequence.getFrameCount())
                                .$(", valid=").$(frameSequence.isValid())
                                .$(", cursor=").$(cursor)
                                .I$();
                        if (frameSequence.isValid()) {
                            // we deliberately hold the queue item because
                            // processing is daisy-chained. If we were to release item before
                            // finishing reduction, next step (job) will be processing an incomplete task
                            record.of(frameSequence.getSymbolTableSource(), frameSequence.getPageAddressCache());
                            record.setFrameIndex(task.getFrameIndex());
                            assert frameSequence.doneLatch.getCount() == 0;
                            frameSequence.getReducer().reduce(record, task);
                        }
                    } catch (Throwable e) {
                        frameSequence.setValid(false);
                        throw e;
                    } finally {
                        framesReducedCounter.incrementAndGet();
                    }
                } finally {
                    subSeq.done(cursor);
                }
                return false;
            } else if (cursor == -1) {
                // queue is full, we should yield or help
                break;
            }
        } while (true);
        return true;
    }

    @Override
    public boolean run(int workerId) {
        boolean useful = false;
        for (int i = 0; i < shardCount; i++) {
            final int shard = shards[i];
            useful = !consumeQueue(
                    messageBus.getPageFrameReduceQueue(shard),
                    messageBus.getPageFrameReduceSubSeq(shard),
                    record[workerId]
            ) || useful;
        }
        return useful;
    }
}