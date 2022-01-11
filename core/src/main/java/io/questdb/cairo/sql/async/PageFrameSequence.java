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
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.mp.*;
import io.questdb.std.LongList;
import io.questdb.std.Misc;
import io.questdb.std.Mutable;
import io.questdb.std.Rnd;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class PageFrameSequence<T> implements Mutable {
    public final SOUnboundedCountDownLatch doneLatch = new SOUnboundedCountDownLatch();
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicInteger reduceCounter = new AtomicInteger(0);
    private final LongList frameRowCounts = new LongList();
    private final PageFrameReducer reducer;
    private final PageAddressCache pageAddressCache;
    private final MessageBus messageBus;
    private long id;
    private int shard;
    private int frameCount;
    private SCSequence collectSubSeq;
    private PageFrameCursor pageFrameCursor;
    private T atom;
    private PageAddressCacheRecord[] records;
    // we need this to restart execution for `toTop`
    private MPSequence dispatchPubSeq;
    private RingQueue<PageFrameDispatchTask> pageFrameDispatchQueue;
    private int dispatchStartIndex = 0;

    public PageFrameSequence(CairoConfiguration configuration, MessageBus messageBus, PageFrameReducer reducer) {
        this.reducer = reducer;
        this.pageAddressCache = new PageAddressCache(configuration);
        this.messageBus = messageBus;
    }

    public void await() {
        while (doneLatch.getCount() == 0) {
            stealDispatchQueue();
            PageFrameDispatchJob.stealWork(
                    messageBus,
                    shard,
                    messageBus.getPageFrameReduceQueue(shard),
                    messageBus.getPageFrameReduceSubSeq(shard),
                    messageBus.getPageFrameCleanupSubSeq(shard),
                    records[getWorkerId()]
            );
        }
        frameCount = 0;
    }

    @Override
    public void clear() {
        this.id = -1;
        this.shard = -1;
        this.frameCount = 0;
        pageAddressCache.clear();
        frameRowCounts.clear();
        pageFrameCursor = Misc.free(pageFrameCursor);
        collectSubSeq.clear();
        doneLatch.countDown();
        this.dispatchStartIndex = 0;
    }

    public boolean stealDispatchQueue() {

        // todo: this must be called in locking mode,
        //     we must not dispatch the same task twice
        PageFrameDispatchJob.handleTask(
                this,
                records[getWorkerId()],
                messageBus,
                true
        );
        // We need to handle task regardless of that being on the queue or not;
        // the state of the task is stored on the page frame sequence instance
        // thus making dispatch code rentable.
        final MCSequence dispatchSubSeq = messageBus.getPageFrameDispatchSubSeq();

        // we also need to click dispatch task if it appears on the queue
        final long c = dispatchSubSeq.next();
        if (c > -1) {
            dispatchSubSeq.done(c);
            return false;
        }
        return true;
    }

    public PageFrameSequence<T> dispatch(
            RecordCursorFactory base,
            SqlExecutionContext executionContext,
            SCSequence collectSubSeq,
            T atom
    ) throws SqlException {

        // allow entry for 0 - main thread that is a non-worker
        initWorkerRecords(executionContext.getWorkerCount() + 1);

        final Rnd rnd = executionContext.getAsyncRandom();
        final MessageBus bus = executionContext.getMessageBus();
        // before thread begins we will need to pick a shard
        // of queues that we will interact with
        final int shard = rnd.nextInt(bus.getPageFrameReduceShardCount());
        final PageFrameCursor pageFrameCursor = base.getPageFrameCursor(executionContext);
        final MPSequence dispatchPubSeq = bus.getPageFrameDispatchPubSeq();
        final RingQueue<PageFrameDispatchTask> pageFrameDispatchQueue = bus.getPageFrameDispatchQueue();

        // pass one to cache page addresses
        // this has to be separate pass to ensure there no cache reads
        // while cache might be resizing
        this.pageAddressCache.of(base.getMetadata());

        PageFrame frame;
        int frameIndex = 0;
        while ((frame = pageFrameCursor.next()) != null) {
            this.pageAddressCache.add(frameIndex++, frame);
            frameRowCounts.add(frame.getPartitionHi() - frame.getPartitionLo());
        }

        of(
                shard,
                rnd.nextLong(),
                frameIndex,
                collectSubSeq,
                pageFrameCursor,
                atom,
                dispatchPubSeq,
                pageFrameDispatchQueue
        );

        // dispatch message only if there is anything to dispatch
        if (frameIndex > 0) {
            long dispatchCursor;
            do {
                dispatchCursor = dispatchPubSeq.next();
                if (dispatchCursor < 0 && stealDispatchQueue()) {
                    LockSupport.parkNanos(1);
                } else {
                    break;
                }
            } while (true);

            // We need to subscribe publisher sequence before we return
            // control to the caller of this method. However, this sequence
            // will be unsubscribed asynchronously.
            bus.getPageFrameCollectFanOut(shard).and(collectSubSeq);

            PageFrameDispatchTask dispatchTask = pageFrameDispatchQueue.get(dispatchCursor);
            dispatchTask.of(this);
            dispatchPubSeq.done(dispatchCursor);
        } else {
            // non-dispatched frames will leave page frame cursor and reader dangling if not freed
            pageFrameCursor.close();
        }
        return this;
    }

    public T getAtom() {
        return atom;
    }

    public SCSequence getCollectSubSeq() {
        return collectSubSeq;
    }

    public int getDispatchStartIndex() {
        return dispatchStartIndex;
    }

    public void setDispatchStartIndex(int i) {
        this.dispatchStartIndex = i;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public long getFrameRowCount(int frameIndex) {
        return frameRowCounts.getQuick(frameIndex);
    }

    public long getId() {
        return id;
    }

    public PageAddressCache getPageAddressCache() {
        return pageAddressCache;
    }

    public AtomicInteger getReduceCounter() {
        return reduceCounter;
    }

    public PageFrameReducer getReducer() {
        return reducer;
    }

    public int getShard() {
        return shard;
    }

    public SymbolTableSource getSymbolTableSource() {
        return pageFrameCursor;
    }

    public boolean isValid() {
        return valid.get();
    }

    public void setValid(boolean valid) {
        this.valid.compareAndSet(true, valid);
    }

    public void toTop() {
        if (frameCount > 0) {
            this.pageFrameCursor.toTop();
            long dispatchCursor;
            do {
                dispatchCursor = dispatchPubSeq.next();
                if (dispatchCursor < 0 && stealDispatchQueue()) {
                    LockSupport.parkNanos(1);
                } else {
                    break;
                }
            } while (true);
            final PageFrameDispatchTask dispatchTask = pageFrameDispatchQueue.get(dispatchCursor);
            dispatchTask.of(this);
            dispatchPubSeq.done(dispatchCursor);
        }
    }

    private static int getWorkerId() {
        final Thread thread = Thread.currentThread();
        final int workerId;
        if (thread instanceof Worker) {
            workerId = ((Worker) thread).getWorkerId() + 1;
        } else {
            workerId = 0;
        }
        return workerId;
    }

    private void initWorkerRecords(int workerCount) {
        if (records == null || records.length < workerCount) {
            this.records = new PageAddressCacheRecord[workerCount];
            for (int i = 0; i < workerCount; i++) {
                this.records[i] = new PageAddressCacheRecord();
            }
        }
    }

    private void of(
            int shard,
            long frameSequenceId,
            int frameCount,
            SCSequence collectSubSeq,
            PageFrameCursor symbolTableSource,
            T atom,
            MPSequence dispatchPubSeq,
            RingQueue<PageFrameDispatchTask> pageFrameDispatchQueue
    ) {
        this.id = frameSequenceId;
        this.doneLatch.reset();
        this.valid.set(true);
        this.reduceCounter.set(0);
        this.shard = shard;
        this.frameCount = frameCount;
        this.collectSubSeq = collectSubSeq;
        this.pageFrameCursor = symbolTableSource;
        this.atom = atom;
        this.dispatchPubSeq = dispatchPubSeq;
        this.pageFrameDispatchQueue = pageFrameDispatchQueue;
    }
}
