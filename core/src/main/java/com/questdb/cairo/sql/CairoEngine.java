/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2018 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cairo.sql;

import com.questdb.cairo.TableReader;
import com.questdb.cairo.TableWriter;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

public interface CairoEngine extends Closeable {
    TableReader getReader(CharSequence tableName);

    int getStatus(CharSequence tableName, int lo, int hi);

    default int getStatus(CharSequence tableName) {
        return getStatus(tableName, 0, tableName.length());
    }

    boolean releaseAllReaders();

    boolean releaseAllWriters();

    boolean lock(CharSequence tableName);

    void unlock(CharSequence tableName, @Nullable TableWriter writer);

    TableWriter getWriter(CharSequence tableName);
}