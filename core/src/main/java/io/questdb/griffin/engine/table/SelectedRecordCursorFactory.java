/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
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

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.BitmapIndexReader;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.PageFrame;
import io.questdb.cairo.sql.PageFrameCursor;
import io.questdb.cairo.sql.PartitionFormat;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.cairo.sql.StaticSymbolTable;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.cairo.vm.api.MemoryCARW;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.jit.CompiledFilter;
import io.questdb.std.IntList;
import io.questdb.std.ObjList;
import org.jetbrains.annotations.Nullable;

public class SelectedRecordCursorFactory extends AbstractRecordCursorFactory {

    private final RecordCursorFactory base;
    private final IntList columnCrossIndex;
    private final SelectedRecordCursor cursor;
    private SelectedPageFrameCursor pageFrameCursor;

    public SelectedRecordCursorFactory(RecordMetadata metadata, IntList columnCrossIndex, RecordCursorFactory base) {
        super(metadata);
        this.base = base;
        this.columnCrossIndex = columnCrossIndex;
        this.cursor = new SelectedRecordCursor(columnCrossIndex, base.recordCursorSupportsRandomAccess());
    }

    @Override
    public boolean followedLimitAdvice() {
        return base.followedLimitAdvice();
    }

    @Override
    public boolean followedOrderByAdvice() {
        return base.followedOrderByAdvice();
    }

    @Override
    public RecordCursorFactory getBaseFactory() {
        return base;
    }

    // to be used in combination with compiled filter
    @Nullable
    public ObjList<Function> getBindVarFunctions() {
        return base.getBindVarFunctions();
    }

    // to be used in combination with compiled filter
    @Nullable
    public MemoryCARW getBindVarMemory() {
        return base.getBindVarMemory();
    }

    @Override
    public CompiledFilter getCompiledFilter() {
        return base.getCompiledFilter();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        final RecordCursor baseCursor = base.getCursor(executionContext);
        try {
            cursor.of(baseCursor);
            return cursor;
        } catch (Throwable th) {
            cursor.close();
            throw th;
        }
    }

    @Override
    public Function getFilter() {
        return base.getFilter();
    }

    @Override
    public PageFrameCursor getPageFrameCursor(SqlExecutionContext executionContext, int order) throws SqlException {
        PageFrameCursor baseCursor = base.getPageFrameCursor(executionContext, order);
        if (baseCursor == null) {
            return null;
        }
        if (pageFrameCursor == null) {
            pageFrameCursor = new SelectedPageFrameCursor(columnCrossIndex);
        }
        return pageFrameCursor.wrap(baseCursor);
    }

    @Override
    public int getScanDirection() {
        return base.getScanDirection();
    }

    @Override
    public void halfClose() {
        base.halfClose();
    }

    @Override
    public boolean implementsLimit() {
        return base.implementsLimit();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return base.recordCursorSupportsRandomAccess();
    }

    @Override
    public boolean supportsPageFrameCursor() {
        return base.supportsPageFrameCursor();
    }

    @Override
    public boolean supportsUpdateRowId(TableToken tableToken) {
        return base.supportsUpdateRowId(tableToken);
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("SelectedRecord");
        sink.child(base);
    }

    @Override
    public boolean usesCompiledFilter() {
        return base.usesCompiledFilter();
    }

    @Override
    public boolean usesIndex() {
        return base.usesIndex();
    }

    @Override
    protected void _close() {
        base.close();
    }

    private static class SelectedPageFrame implements PageFrame {
        private final IntList columnCrossIndex;
        private PageFrame baseFrame;

        private SelectedPageFrame(IntList columnCrossIndex) {
            this.columnCrossIndex = columnCrossIndex;
        }

        @Override
        public long getAuxPageAddress(int columnIndex) {
            return baseFrame.getAuxPageAddress(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public long getAuxPageSize(int columnIndex) {
            return baseFrame.getAuxPageSize(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public BitmapIndexReader getBitmapIndexReader(int columnIndex, int direction) {
            return baseFrame.getBitmapIndexReader(columnCrossIndex.getQuick(columnIndex), direction);
        }

        @Override
        public int getColumnCount() {
            return columnCrossIndex.size();
        }

        @Override
        public byte getFormat() {
            return baseFrame.getFormat();
        }

        @Override
        public long getPageAddress(int columnIndex) {
            return baseFrame.getPageAddress(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public long getPageSize(int columnIndex) {
            return baseFrame.getPageSize(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public long getParquetAddr() {
            return baseFrame.getParquetAddr();
        }

        @Override
        public long getParquetFileSize() {
            final long fileSize = baseFrame.getParquetFileSize();
            assert fileSize > 0 || baseFrame.getFormat() != PartitionFormat.PARQUET;
            return fileSize;
        }

        @Override
        public int getParquetRowGroup() {
            return baseFrame.getParquetRowGroup();
        }

        @Override
        public int getParquetRowGroupHi() {
            return baseFrame.getParquetRowGroupHi();
        }

        @Override
        public int getParquetRowGroupLo() {
            return baseFrame.getParquetRowGroupLo();
        }

        @Override
        public long getPartitionHi() {
            return baseFrame.getPartitionHi();
        }

        @Override
        public int getPartitionIndex() {
            return baseFrame.getPartitionIndex();
        }

        @Override
        public long getPartitionLo() {
            return baseFrame.getPartitionLo();
        }

        public SelectedPageFrame of(PageFrame basePageFrame) {
            this.baseFrame = basePageFrame;
            return this;
        }
    }

    private static class SelectedPageFrameCursor implements PageFrameCursor {
        private final IntList columnCrossIndex;
        private final SelectedPageFrame pageFrame;
        private PageFrameCursor baseCursor;

        private SelectedPageFrameCursor(IntList columnCrossIndex) {
            this.columnCrossIndex = columnCrossIndex;
            this.pageFrame = new SelectedPageFrame(columnCrossIndex);
        }

        @Override
        public void calculateSize(RecordCursor.Counter counter) {
            baseCursor.calculateSize(counter);
        }

        @Override
        public void close() {
            baseCursor.close();
        }

        @Override
        public IntList getColumnIndexes() {
            return baseCursor.getColumnIndexes();
        }

        @Override
        public StaticSymbolTable getSymbolTable(int columnIndex) {
            return baseCursor.getSymbolTable(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public SymbolTable newSymbolTable(int columnIndex) {
            return baseCursor.newSymbolTable(columnCrossIndex.getQuick(columnIndex));
        }

        @Override
        public @Nullable PageFrame next() {
            PageFrame baseFrame = baseCursor.next();
            return baseFrame != null ? pageFrame.of(baseFrame) : null;
        }

        @Override
        public long size() {
            return baseCursor.size();
        }

        @Override
        public boolean supportsSizeCalculation() {
            return baseCursor.supportsSizeCalculation();
        }

        @Override
        public void toTop() {
            baseCursor.toTop();
        }

        public SelectedPageFrameCursor wrap(PageFrameCursor baseCursor) {
            this.baseCursor = baseCursor;
            return this;
        }
    }
}
