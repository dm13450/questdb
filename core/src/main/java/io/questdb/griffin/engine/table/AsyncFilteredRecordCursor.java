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

package io.questdb.griffin.engine.table;

import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.async.PageFrameReduceTask;
import io.questdb.cairo.sql.async.PageFrameSequence;
import io.questdb.griffin.SqlException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SCSequence;
import io.questdb.std.DirectLongList;
import io.questdb.std.Rows;

class AsyncFilteredRecordCursor implements RecordCursor {
    private final static Log LOG = LogFactory.getLog(AsyncFilteredRecordCursor.class);
    private final Function filter;
    private final PageAddressCacheRecord record;
    private PageAddressCacheRecord recordB;
    private SCSequence collectSubSeq;
    private RingQueue<PageFrameReduceTask> reduceQueue;
    private DirectLongList rows;
    private long cursor = -1;
    private long frameRowIndex;
    private long frameRowCount;
    private int frameIndex;
    private int frameLimit;
    private PageFrameSequence<?> frameSequence;
    // Artificial limit on remaining rows to be returned from this cursor.
    // It is typically copied from 'limit' clause on SQL statement
    private long rowsRemaining;

    public AsyncFilteredRecordCursor(Function filter) {
        this.filter = filter;
        this.record = new PageAddressCacheRecord();
    }

    @Override
    public void close() {
        LOG.debug()
                .$("closing [shard=").$(frameSequence.getShard())
                .$(", frameIndex=").$(frameIndex)
                .$(", frameCount=").$(frameLimit)
                .$(", cursor=").$(cursor)
                .I$();

        collectCursor();
        if (frameLimit > -1) {
            frameSequence.await();
        }
        frameSequence.clear();
    }

    @Override
    public Record getRecord() {
        return record;
    }

    @Override
    public SymbolTable getSymbolTable(int columnIndex) {
        return frameSequence.getSymbolTableSource().getSymbolTable(columnIndex);
    }

    @Override
    public boolean hasNext() {
        // we have rows in the current frame we still need to dispatch
        if (frameRowIndex < frameRowCount) {
            record.setRowIndex(rows.get(frameRowIndex++));
            return checkLimit();
        }

        // Release previous queue item.
        // There is no identity check here because this check
        // had been done when 'cursor' was assigned
        collectCursor();

        // do we have more frames?
        if (frameIndex < frameLimit) {
            fetchNextFrame();
            if (frameRowCount > 0) {
                record.setRowIndex(rows.get(frameRowIndex++));
                return checkLimit();
            }
        }
        return false;
    }

    @Override
    public Record getRecordB() {
        if (recordB != null) {
            return recordB;
        }
        recordB = new PageAddressCacheRecord(record);
        return recordB;
    }

    @Override
    public void recordAt(Record record, long atRowId) {
        ((PageAddressCacheRecord) record).setFrameIndex(Rows.toPartitionIndex(atRowId));
        ((PageAddressCacheRecord) record).setRowIndex(Rows.toLocalRowID(atRowId));
    }

    @Override
    public void toTop() {
        // check if we at the top already and there is nothing to do
        if (frameIndex == 0 && frameRowIndex == 0) {
            return;
        }
        filter.toTop();
        frameSequence.toTop();
        if (frameLimit > -1) {
            frameIndex = -1;
            fetchNextFrame();
        }
    }

    @Override
    public long size() {
        return -1;
    }

    private boolean checkLimit() {
        if (--rowsRemaining < 0) {
            frameSequence.setValid(false);
            return false;
        }
        return true;
    }

    private void collectCursor() {
        if (cursor > -1) {
            unsafeCollectCursor();
        }
    }

    private void fetchNextFrame() {
        do {
            this.cursor = collectSubSeq.next();
            if (cursor > -1) {
                PageFrameReduceTask task = reduceQueue.get(cursor);
                PageFrameSequence<?> thatFrameSequence = task.getFrameSequence();
                if (thatFrameSequence == this.frameSequence) {

                    LOG.debug()
                            .$("collected [shard=").$(frameSequence.getShard())
                            .$(", frameIndex=").$(task.getFrameIndex())
                            .$(", frameCount=").$(frameSequence.getFrameCount())
                            .$(", valid=").$(frameSequence.isValid())
                            .$(", cursor=").$(cursor)
                            .I$();
                    this.rows = task.getRows();
                    this.frameRowCount = rows.size();
                    this.frameIndex = task.getFrameIndex();
                    if (this.frameRowCount > 0 && frameSequence.isValid()) {
                        this.frameRowIndex = 0;
                        record.setFrameIndex(task.getFrameIndex());
                        break;
                    } else {
                        // It is necessary to clear 'cursor' value
                        // because we updated frameIndex and loop can exit due to lack of frames.
                        // Non-update of 'cursor' could cause double-free.
                        unsafeCollectCursor();
                    }
                } else {
                    // not our task, nothing to collect
                    collectSubSeq.done(cursor);
                }
            } else {
                frameSequence.stealDispatchWork();
            }
        } while (this.frameIndex < frameLimit);
    }

    void of(SCSequence collectSubSeq, PageFrameSequence<?> frameSequence, long rowsRemaining) throws SqlException {
        this.collectSubSeq = collectSubSeq;
        this.frameSequence = frameSequence;
        this.reduceQueue = frameSequence.getPageFrameReduceQueue();
        this.frameIndex = -1;
        this.frameLimit = frameSequence.getFrameCount() - 1;
        this.rowsRemaining = rowsRemaining;
        record.of(frameSequence.getSymbolTableSource(), frameSequence.getPageAddressCache());
        // when frameCount is 0 our collect sequence is not subscribed
        // we should not be attempting to fetch queue using it
        if (frameLimit > -1) {
            fetchNextFrame();
        }
    }

    private void unsafeCollectCursor() {
        reduceQueue.get(cursor).collected();
        collectSubSeq.done(cursor);
        cursor = -1;
    }
}