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

package io.questdb.cairo.mig;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.vm.api.MemoryARW;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.std.FilesFacade;
import io.questdb.std.str.Path;

import static io.questdb.cairo.TableUtils.*;

public class Mig662 {

    static void migrate(MigrationContext context) {
        // Update transaction file
        // Before there were 4 longs per partition entry in the partition table,
        // now there are 8. The 5th is the partition mask, whose 64th bit flags
        // the partition as read-only when set. Prior to version 427 all partitions 
        // were read-write

        final FilesFacade ff = context.getFf();
        final Path path = context.getTablePath();   // preloaded with tha table's path
        final int pathLen = path.length();
        final Path other = context.getTablePath2().of(path).trimTo(pathLen);

        // make sure we are migrating from 426
        assert 426 == TableUtils.readIntOrFail(ff, context.getMetadataFd(), META_OFFSET_VERSION, context.getTempMemory(Long.BYTES), path);

        // backup current _txn file to _txn.v426
        path.concat(TableUtils.TXN_FILE_NAME).$();
        if (!ff.exists(path)) {
            MigrationActions.LOG.error().$("tx file does not exist, nothing to migrate [path=").$(path).I$();
            return;
        }
        EngineMigration.backupFile(ff, path, other, TableUtils.TXN_FILE_NAME, 426);

        // open _txn file for RW
        final int newPartitionSegmentSize;
        try (MemoryMARW txFile = context.createRwMemoryOf(ff, path)) {

            final long version = txFile.getLong(TX_BASE_OFFSET_VERSION_64);
            boolean isA = (version & 1L) == 0L; // formality for readability, as I know the version I expect and whether it is even/odd
            final long partitionSegmentSizeOffset = isA ? TX_BASE_OFFSET_PARTITIONS_SIZE_A_32 : TX_BASE_OFFSET_PARTITIONS_SIZE_B_32;
            final int partitionSegmentSize = txFile.getInt(partitionSegmentSizeOffset);
            newPartitionSegmentSize = partitionSegmentSize * 2;

            MigrationActions.LOG.info().$("extending partition table on tx file [table=").utf8(context.getTablePath())
                    .$(", version=").$(version)
                    .$(", from_size=").$(partitionSegmentSize)
                    .$(", to_size=").$(newPartitionSegmentSize)
                    .$(", from_partitions=").$(partitionSegmentSize / (4 * Long.BYTES)) // version 426 -> 4 longs
                    .$(", to_partitions=").$(newPartitionSegmentSize / (8 * Long.BYTES)) // version 427 -> 8 longs
                    .I$();

            if (partitionSegmentSize > 0) {

                // read/extend current partition table
                MemoryARW txFileUpdate = context.getTempVirtualMem();
                txFileUpdate.jumpTo(0);
                final int symbolsSize = txFile.getInt(isA ? TX_BASE_OFFSET_SYMBOLS_SIZE_A_32 : TX_BASE_OFFSET_SYMBOLS_SIZE_B_32);
                final int baseOffset = txFile.getInt(isA ? TX_BASE_OFFSET_A_32 : TX_BASE_OFFSET_B_32);
                final long partitionSegmentOffset = baseOffset + TX_OFFSET_MAP_WRITER_COUNT_32 + Integer.BYTES + symbolsSize + Integer.BYTES;
                for (int i = 0; i < partitionSegmentSize; i += Long.BYTES) { // for each long
                    txFileUpdate.putLong(txFile.getLong(partitionSegmentOffset + i));
                    if ((i + Long.BYTES) % (4 * Long.BYTES) == 0) {
                        txFileUpdate.putLong(0L); // mask
                        txFileUpdate.putLong(0L); // available0
                        txFileUpdate.putLong(0L); // available1
                        txFileUpdate.putLong(0L); // available2
                    }
                }

                // overwrite partition table with patched version
                txFile.putInt(partitionSegmentSizeOffset, newPartitionSegmentSize); // A/B header
                txFile.jumpTo(partitionSegmentOffset - Integer.BYTES);
                txFile.putInt(newPartitionSegmentSize);
                long updateSize = txFileUpdate.getAppendOffset();
                int pageId = 0;
                txFile.jumpTo(partitionSegmentOffset);
                while (updateSize > 0) {
                    long writeSize = Math.min(updateSize, txFileUpdate.getPageSize());
                    txFile.putBlockOfBytes(txFileUpdate.getPageAddress(pageId++), writeSize);
                    updateSize -= writeSize;
                }
            }
        }

        // read back the _txn file to verify it
        try (MemoryMARW txFile = context.createRwMemoryOf(ff, path)) {
            final long version = txFile.getLong(TX_BASE_OFFSET_VERSION_64);
            boolean isA = (version & 1L) == 0L;
            final long partitionSegmentSizeOffset = isA ? TX_BASE_OFFSET_PARTITIONS_SIZE_A_32 : TX_BASE_OFFSET_PARTITIONS_SIZE_B_32;
            final int symbolsSize = txFile.getInt(isA ? TX_BASE_OFFSET_SYMBOLS_SIZE_A_32 : TX_BASE_OFFSET_SYMBOLS_SIZE_B_32);
            final int baseOffset = txFile.getInt(isA ? TX_BASE_OFFSET_A_32 : TX_BASE_OFFSET_B_32);
            final long partitionSegmentOffset = baseOffset + TX_OFFSET_MAP_WRITER_COUNT_32 + Integer.BYTES + symbolsSize;
            if (txFile.getInt(partitionSegmentSizeOffset) != newPartitionSegmentSize || txFile.getInt(partitionSegmentOffset) != newPartitionSegmentSize) {
                throw CairoException.critical(0).put("could not extend partition table on tx file [table=").put(context.getTablePath()).put(']');
            }
        }
    }
}
