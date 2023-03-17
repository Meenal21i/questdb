/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
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

import io.questdb.cairo.*;
import io.questdb.cairo.sql.NoRandomAccessRecordCursor;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordMetadata;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.str.SizePrettyFunctionFactory;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.str.Path;
import io.questdb.std.str.StringSink;

import java.io.Closeable;
import java.util.Comparator;

public class ShowPartitionsRecordCursorFactory extends AbstractRecordCursorFactory {

    private static final Comparator<String> CHAR_COMPARATOR = Chars::compare;
    private static final Log LOG = LogFactory.getLog(ShowPartitionsRecordCursor.class);
    private static final RecordMetadata METADATA;
    private final ShowPartitionsRecordCursor cursor = new ShowPartitionsRecordCursor();
    private final TableToken tableToken;
    private SqlExecutionContext executionContext;

    public ShowPartitionsRecordCursorFactory(TableToken tableToken) {
        super(METADATA);
        this.tableToken = tableToken;
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        this.executionContext = executionContext;
        return cursor.initialize();
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return false;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("show_partitions").meta("of").val(tableToken);
    }

    @Override
    protected void _close() {
        Misc.free(cursor);
    }

    private enum Column {
        PARTITION_INDEX(0, "index", ColumnType.INT),
        PARTITION_BY(1, "partitionBy", ColumnType.STRING),
        PARTITION_NAME(2, "name", ColumnType.STRING),
        MIN_TIMESTAMP(3, "minTimestamp", ColumnType.TIMESTAMP),
        MAX_TIMESTAMP(4, "maxTimestamp", ColumnType.TIMESTAMP),
        NUM_ROWS(5, "numRows", ColumnType.LONG),
        DISK_SIZE(6, "diskSize", ColumnType.LONG),
        DISK_SIZE_HUMAN(7, "diskSizeHuman", ColumnType.STRING),
        IS_READ_ONLY(8, "readOnly", ColumnType.BOOLEAN),
        IS_ACTIVE(9, "active", ColumnType.BOOLEAN),
        IS_ATTACHED(10, "attached", ColumnType.BOOLEAN),
        IS_DETACHED(11, "detached", ColumnType.BOOLEAN),
        IS_ATTACHABLE(12, "attachable", ColumnType.BOOLEAN);

        private final int idx;
        private final TableColumnMetadata metadata;

        Column(int idx, String name, int type) {
            this.idx = idx;
            this.metadata = new TableColumnMetadata(name, type);
        }

        boolean is(int idx) {
            return this.idx == idx;
        }

        TableColumnMetadata metadata() {
            return metadata;
        }
    }

    private class ShowPartitionsRecordCursor implements NoRandomAccessRecordCursor {
        private final ObjList<String> attachablePartitions = new ObjList<>(4);
        private final ObjList<String> detachedPartitions = new ObjList<>(8);
        private final StringSink partitionName = new StringSink();
        private final PartitionsRecord partitionRecord = new PartitionsRecord();
        private TableReaderMetadata detachedMetaReader;
        private TxReader detachedTxReader;
        private int dynamicPartitionIndex = -1;
        private boolean isActive;
        private boolean isAttachable;
        private boolean isDetached;
        private boolean isReadOnly;
        private int limit; // partitionCount + detached + attachable
        private long maxTimestamp = Long.MIN_VALUE;
        private long minTimestamp = Long.MIN_VALUE; // so that in absence of metadata is NaN
        private long numRows = -1L;
        private int partitionBy = -1;
        private int partitionIndex = -1;
        private long partitionSize = -1L;
        private TableReader tableReader;
        private CharSequence tsColName;

        @Override
        public void close() {
            detachedMetaReader = Misc.free(detachedMetaReader);
            detachedTxReader = Misc.free(detachedTxReader);
            attachablePartitions.clear();
            detachedPartitions.clear();
            partitionName.clear();
            partitionRecord.close();
            tableReader = Misc.free(tableReader);
        }

        @Override
        public Record getRecord() {
            return partitionRecord;
        }

        @Override
        public boolean hasNext() {
            if (++partitionIndex < limit) {
                loadNextPartition();
                return true;
            }
            --partitionIndex;
            return false;
        }

        @Override
        public long size() {
            return limit;
        }

        @Override
        public void toTop() {
            partitionIndex = -1;
        }

        private ShowPartitionsRecordCursor initialize() {
            if (tableReader != null) {
                // this call is idempotent
                return this;
            }
            tsColName = null;
            tableReader = executionContext.getReader(tableToken);
            partitionBy = tableReader.getPartitionedBy();
            if (PartitionBy.isPartitioned(partitionBy)) {
                TableReaderMetadata meta = tableReader.getMetadata();
                tsColName = meta.getColumnName(meta.getTimestampIndex());
            }
            scanDetachedAndAttachablePartitions();
            limit = tableReader.getTxFile().getPartitionCount() + attachablePartitions.size() + detachedPartitions.size();
            toTop();
            return this;
        }

        private void loadNextPartition() {
            isReadOnly = false;
            isActive = false;
            isDetached = false;
            isAttachable = false;
            minTimestamp = Long.MIN_VALUE; // so that in absence of metadata is NaN
            maxTimestamp = Long.MIN_VALUE;
            numRows = -1L;
            partitionSize = -1L;
            partitionName.clear();
            dynamicPartitionIndex = partitionIndex;
            CharSequence dynamicTsColName = tsColName;

            CairoConfiguration cairoConfig = executionContext.getCairoEngine().getConfiguration();
            FilesFacade ff = cairoConfig.getFilesFacade();
            Path path = Path.getThreadLocal(cairoConfig.getRoot()).concat(tableToken.getDirName()).$();
            TxReader tableTxReader = tableReader.getTxFile();
            int partitionCount = tableTxReader.getPartitionCount();

            if (partitionIndex < partitionCount) {
                // we are within the partition table
                isReadOnly = tableTxReader.isPartitionReadOnly(partitionIndex);
                long timestamp = tableTxReader.getPartitionTimestamp(partitionIndex);
                isActive = timestamp == tableTxReader.getLastPartitionTimestamp();
                PartitionBy.setSinkForPartition(partitionName, partitionBy, timestamp, false);
                TableUtils.txnPartitionConditionally(path.concat(partitionName), tableTxReader.getPartitionNameTxn(partitionIndex));
                numRows = tableTxReader.getPartitionSize(partitionIndex);
            } else {
                // partition table is over, we will iterate over detached and attachable partitions
                isDetached = true;
                int idx = partitionIndex - partitionCount; // index in detachedPartitions
                int n = detachedPartitions.size();
                if (idx < n) {
                    // is detached
                    partitionName.put(detachedPartitions.get(idx));
                } else {
                    idx -= n; // index in attachablePartitions
                    if (idx < attachablePartitions.size()) {
                        // is attachable, also detached
                        partitionName.put(attachablePartitions.get(idx));
                        isAttachable = true;
                    }
                }
                assert partitionName.length() != 0;

                // open detached meta files (_meta, _txn) if they exist
                dynamicPartitionIndex = Integer.MIN_VALUE; // so that in absence of metadata is NaN
                if (ff.exists(path.concat(partitionName).concat(TableUtils.META_FILE_NAME).$())) {
                    try {
                        if (detachedMetaReader == null) {
                            detachedMetaReader = new TableReaderMetadata(cairoConfig);
                        }
                        detachedMetaReader.load(path);
                        if (tableToken.getTableId() == detachedMetaReader.getTableId() && partitionBy == detachedMetaReader.getPartitionBy()) {
                            if (ff.exists(path.parent().concat(TableUtils.TXN_FILE_NAME).$())) {
                                try {
                                    if (detachedTxReader == null) {
                                        detachedTxReader = new TxReader(FilesFacadeImpl.INSTANCE);
                                    }
                                    detachedTxReader.ofRO(path, partitionBy);
                                    detachedTxReader.unsafeLoadAll();
                                    long timestamp = PartitionBy.parsePartitionDirName(partitionName, partitionBy);
                                    int pIndex = detachedTxReader.getPartitionIndex(timestamp);
                                    // could set dynamicPartitionIndex to -pIndex
                                    numRows = detachedTxReader.getPartitionSize(pIndex);
                                    if (PartitionBy.isPartitioned(partitionBy) && numRows > 0L) {
                                        int tsIndex = detachedMetaReader.getTimestampIndex();
                                        dynamicTsColName = detachedMetaReader.getColumnName(tsIndex);
                                    }
                                } finally {
                                    detachedTxReader.clear();
                                }
                            } else {
                                LOG.error().$("detached partition does not have meta file [path=").$(path).I$();
                            }
                        } else {
                            LOG.error().$("detached partition meta does not match [path=").$(path).I$();
                        }
                    } finally {
                        detachedMetaReader.clear();
                    }
                } else {
                    LOG.error().$("detached partition does not have meta file [path=").$(path).I$();
                }
                path.parent();
            }

            partitionSize = ff.getDirectoryContentSize(path.$());
            if (PartitionBy.isPartitioned(partitionBy) && numRows > 0L) {
                TableUtils.dFile(path.slash$(), dynamicTsColName, TableUtils.COLUMN_NAME_TXN_NONE);
                int fd = -1;
                try {
                    fd = TableUtils.openRO(ff, path, LOG);
                    long lastOffset = (numRows - 1) * ColumnType.sizeOf(ColumnType.TIMESTAMP);
                    minTimestamp = ff.readNonNegativeLong(fd, 0);
                    maxTimestamp = ff.readNonNegativeLong(fd, lastOffset);
                } catch (CairoException e) {
                    if (partitionIndex < partitionCount) {
                        throw CairoException.critical(ff.errno()).put("no file found for designated timestamp column [path=").put(path).put(']');
                    }
                    dynamicPartitionIndex = Integer.MIN_VALUE;
                    LOG.error().$("no file found for designated timestamp column [path=").$(path).I$();
                } finally {
                    if (fd != -1) {
                        ff.close(fd);
                    }
                }
            }
        }

        private void scanDetachedAndAttachablePartitions() {
            CairoConfiguration cairoConfig = executionContext.getCairoEngine().getConfiguration();
            Path path = Path.getThreadLocal(cairoConfig.getRoot()).concat(tableToken).$();
            FilesFacade ff = cairoConfig.getFilesFacade();
            long pFind = ff.findFirst(path);
            if (pFind > 0L) {
                try {
                    attachablePartitions.clear();
                    detachedPartitions.clear();
                    do {
                        partitionName.clear();
                        Chars.utf8DecodeZ(ff.findName(pFind), partitionName);
                        int type = ff.findType(pFind);
                        if ((type == Files.DT_LNK || type == Files.DT_DIR) && Chars.endsWith(partitionName, TableUtils.ATTACHABLE_DIR_MARKER)) {
                            attachablePartitions.add(Chars.toString(partitionName));
                        } else if (type == Files.DT_DIR && Chars.endsWith(partitionName, TableUtils.DETACHED_DIR_MARKER)) {
                            detachedPartitions.add(Chars.toString(partitionName));
                        }
                    } while (ff.findNext(pFind) > 0);
                    attachablePartitions.sort(CHAR_COMPARATOR);
                    detachedPartitions.sort(CHAR_COMPARATOR);
                } finally {
                    ff.findClose(pFind);
                }
            }
        }

        private class PartitionsRecord implements Record, Closeable {
            @Override
            public void close() {
                // no-op
            }

            @Override
            public boolean getBool(int col) {
                if (Column.IS_READ_ONLY.is(col)) {
                    return isReadOnly;
                }
                if (Column.IS_ACTIVE.is(col)) {
                    return isActive;
                }
                if (Column.IS_ATTACHED.is(col)) {
                    return isReadOnly || !isDetached;
                }
                if (Column.IS_DETACHED.is(col)) {
                    return isDetached;
                }
                if (Column.IS_ATTACHABLE.is(col)) {
                    return isAttachable;
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public int getInt(int col) {
                if (Column.PARTITION_INDEX.is(col)) {
                    return dynamicPartitionIndex;
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public long getLong(int col) {
                if (Column.NUM_ROWS.is(col)) {
                    return numRows;
                }
                if (Column.DISK_SIZE.is(col)) {
                    return partitionSize;
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public CharSequence getStr(int col) {
                if (Column.PARTITION_BY.is(col)) {
                    return PartitionBy.toString(partitionBy);
                }
                if (Column.DISK_SIZE_HUMAN.is(col)) {
                    return SizePrettyFunctionFactory.toSizePretty(partitionSize);
                }
                if (Column.PARTITION_NAME.is(col)) {
                    return partitionName;
                }
                throw new UnsupportedOperationException();
            }

            @Override
            public CharSequence getStrB(int col) {
                return getStr(col);
            }

            @Override
            public int getStrLen(int col) {
                CharSequence s = getStr(col);
                return s != null ? s.length() : TableUtils.NULL_LEN;
            }

            @Override
            public long getTimestamp(int col) {
                if (Column.MIN_TIMESTAMP.is(col)) {
                    return minTimestamp;
                }
                if (Column.MAX_TIMESTAMP.is(col)) {
                    return maxTimestamp;
                }
                throw new UnsupportedOperationException();
            }
        }
    }

    static {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(Column.PARTITION_INDEX.metadata());
        metadata.add(Column.PARTITION_BY.metadata());
        metadata.add(Column.PARTITION_NAME.metadata());
        metadata.add(Column.MIN_TIMESTAMP.metadata());
        metadata.add(Column.MAX_TIMESTAMP.metadata());
        metadata.add(Column.NUM_ROWS.metadata());
        metadata.add(Column.DISK_SIZE.metadata());
        metadata.add(Column.DISK_SIZE_HUMAN.metadata());
        metadata.add(Column.IS_READ_ONLY.metadata());
        metadata.add(Column.IS_ACTIVE.metadata());
        metadata.add(Column.IS_ATTACHED.metadata());
        metadata.add(Column.IS_DETACHED.metadata());
        metadata.add(Column.IS_ATTACHABLE.metadata());
        METADATA = metadata;
    }
}
