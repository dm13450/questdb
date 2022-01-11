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

import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.*;
import io.questdb.cairo.sql.async.PageFrameReduceTask;
import io.questdb.cairo.sql.async.PageFrameSequence;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.mp.RingQueue;
import io.questdb.mp.SCSequence;
import io.questdb.std.DirectLongList;
import io.questdb.std.Rows;

class AsyncFilteredRecordCursor implements RecordCursor {
    private final Function filter;
    private final PageAddressCacheRecord record;
    private PageAddressCacheRecord recordB;
    private SCSequence collectSubSeq;
    private RingQueue<PageFrameReduceTask> queue;
    private DirectLongList rows;
    private long cursor = -1;
    private long frameRowIndex;
    private long frameRowCount;
    private int frameIndex;
    private int frameCount;
    private PageFrameSequence<?> frameSequence;

    public AsyncFilteredRecordCursor(Function filter) {
        this.filter = filter;
        this.record = new PageAddressCacheRecord();
    }

    @Override
    public void close() {
        if (cursor > -1) {
            collectSubSeq.done(cursor);
            cursor = -1;
        }
        if (frameCount > 0) {
            frameSequence.await();
        }
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
            return true;
        }

        // do we have more frames?
        if (frameIndex + 1 < frameCount) {
            // release previous queue item
            collectSubSeq.done(cursor);
            fetchNextFrame();
            if (frameRowCount > 0) {
                record.setRowIndex(rows.get(frameRowIndex++));
                return true;
            }
            return false;
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
        filter.toTop();
        frameSequence.toTop();
        if (frameCount > 0) {
            frameIndex = -1;
            fetchNextFrame();
        }
    }

    @Override
    public long size() {
        return -1;
    }

    private void fetchNextFrame() {
        do {
            this.cursor = collectSubSeq.next();
            if (cursor > -1) {
                PageFrameReduceTask task = queue.get(cursor);
                this.rows = task.getRows();
                this.frameRowCount = rows.size();
                this.frameIndex = task.getFrameIndex();
                if (this.frameRowCount > 0) {
                    this.frameRowIndex = 0;
                    record.setFrameIndex(task.getFrameIndex());
                    break;
                } else {
                    collectSubSeq.done(cursor);
                    cursor = -1;
                }
            } else {
                // multiple reasons for collect task not being ready:
                // 1. dispatch task hasn't been published
                frameSequence.stealDispatchQueue();
            }
        } while (this.frameIndex + 1 < frameCount);
    }

    void of(SqlExecutionContext executionContext, SCSequence collectSubSeq, PageFrameSequence<?> frameSequence) throws SqlException {
        this.frameSequence = frameSequence;
        this.collectSubSeq = collectSubSeq;
        final int shard = frameSequence.getShard();
        this.queue = executionContext.getMessageBus().getPageFrameReduceQueue(shard);
        PageAddressCache pageAddressCache = frameSequence.getPageAddressCache();
        this.frameIndex = -1;
        this.frameCount = frameSequence.getFrameCount();
        record.of(frameSequence.getSymbolTableSource(), pageAddressCache);
        filter.init(this, executionContext);
        // when frameCount is 0 our collect sequence is not subscribed
        // we should not be attempting to fetch queue using it
        if (frameCount > 0) {
            fetchNextFrame();
        }
    }
}
