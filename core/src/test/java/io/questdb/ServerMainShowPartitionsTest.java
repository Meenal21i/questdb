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

package io.questdb;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.OperationFuture;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.table.ShowPartitionsRecordCursorFactory;
import io.questdb.log.LogFactory;
import io.questdb.mp.SOCountDownLatch;
import io.questdb.std.Files;
import io.questdb.std.Misc;
import io.questdb.std.Os;
import io.questdb.std.str.Path;
import org.junit.*;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static io.questdb.griffin.AbstractGriffinTest.assertCursor;
import static io.questdb.griffin.ShowPartitionsTest.replaceSizeToMatchOS;
import static io.questdb.test.tools.TestUtils.assertMemoryLeak;
import static io.questdb.test.tools.TestUtils.insertFromSelectPopulateTableStmt;

@RunWith(Parameterized.class)
public class ServerMainShowPartitionsTest extends AbstractBootstrapTest {

    private static final String EXPECTED = "index\tpartitionBy\tname\tminTimestamp\tmaxTimestamp\tnumRows\tdiskSize\tdiskSizeHuman\treadOnly\tactive\tattached\tdetached\tattachable\n" +
            "0\tDAY\t2023-01-01\t2023-01-01T00:00:00.950399Z\t2023-01-01T23:59:59.822691Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "1\tDAY\t2023-01-02\t2023-01-02T00:00:00.773090Z\t2023-01-02T23:59:59.645382Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "2\tDAY\t2023-01-03\t2023-01-03T00:00:00.595781Z\t2023-01-03T23:59:59.468073Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "3\tDAY\t2023-01-04\t2023-01-04T00:00:00.418472Z\t2023-01-04T23:59:59.290764Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "4\tDAY\t2023-01-05\t2023-01-05T00:00:00.241163Z\t2023-01-05T23:59:59.113455Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "5\tDAY\t2023-01-06\t2023-01-06T00:00:00.063854Z\t2023-01-06T23:59:59.886545Z\t90910\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "6\tDAY\t2023-01-07\t2023-01-07T00:00:00.836944Z\t2023-01-07T23:59:59.709236Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "7\tDAY\t2023-01-08\t2023-01-08T00:00:00.659635Z\t2023-01-08T23:59:59.531927Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "8\tDAY\t2023-01-09\t2023-01-09T00:00:00.482326Z\t2023-01-09T23:59:59.354618Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "9\tDAY\t2023-01-10\t2023-01-10T00:00:00.305017Z\t2023-01-10T23:59:59.177309Z\t90909\tSIZE\tHUMAN\tfalse\tfalse\ttrue\tfalse\tfalse\n" +
            "10\tDAY\t2023-01-11\t2023-01-11T00:00:00.127708Z\t2023-01-11T23:59:59.000000Z\t90909\tSIZE\tHUMAN\tfalse\ttrue\ttrue\tfalse\tfalse\n";
    private static final String firstPartitionName = "2023-01-01";
    private static final int partitionCount = 11;
    private static final int pgPort = PG_PORT + 11;
    private static Path path;

    private final boolean isWal;

    public ServerMainShowPartitionsTest(AbstractCairoTest.WalMode walMode) {
        isWal = (AbstractCairoTest.WalMode.WITH_WAL == walMode);
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() {
//        return Arrays.asList(new Object[][]{{AbstractCairoTest.WalMode.NO_WAL}});
        return Arrays.asList(new Object[][]{{AbstractCairoTest.WalMode.WITH_WAL}, {AbstractCairoTest.WalMode.NO_WAL}});
    }

    @BeforeClass
    public static void setUpStatic() throws Exception {
        AbstractBootstrapTest.setUpStatic();
        path = new Path().of(root).concat("db").$();
        int pathLen = path.length();
        try {
            Files.remove(path.concat("sys.column_versions_purge_log.lock").$());
            Files.remove(path.trimTo(pathLen).concat("telemetry_config.lock").$());
            createDummyConfiguration(
                    HTTP_PORT + 11,
                    HTTP_MIN_PORT + 11,
                    pgPort,
                    ILP_PORT + 11,
                    PropertyKey.CAIRO_WAL_SUPPORTED.getPropertyPath() + "=true");
            path.parent().$();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterClass
    public static void tearDownStatic() throws Exception {
        Misc.free(path);
        AbstractBootstrapTest.tearDownStatic();
    }

    @Test
    public void testServerMainCreateWalTableWhileConcurrentCreateWalTable() throws Exception {
        Assume.assumeFalse(Os.isWindows()); // Windows requires special privileges to create soft links
        String tableName = testTableName(testName.getMethodName());
        assertMemoryLeak(() -> {
            try (
                    ServerMain qdb = new ServerMain("-d", root.toString(), Bootstrap.SWITCH_USE_DEFAULT_LOG_FACTORY_CONFIGURATION);
                    SqlCompiler compiler = new SqlCompiler(qdb.getCairoEngine());
                    SqlExecutionContext defaultContext = executionContext(qdb.getCairoEngine())
            ) {
                qdb.start();
                CairoEngine engine = qdb.getCairoEngine();
                CairoConfiguration cairoConfig = qdb.getConfiguration().getCairoConfiguration();
                TableToken tableToken = createPopulateTable(cairoConfig, engine, compiler, defaultContext, tableName);
                String finallyExpected = replaceSizeToMatchOS(EXPECTED, path, tableToken.getTableName(), engine, Misc.getThreadLocalBuilder());
                int numThreads = 5;
                SOCountDownLatch start = new SOCountDownLatch(1);
                SOCountDownLatch completed = new SOCountDownLatch(numThreads);
                List<SqlExecutionContext> contexts = new ArrayList<>(numThreads);
                for (int i = 0; i < numThreads; i++) {
                    SqlExecutionContext context = executionContext(qdb.getCairoEngine());
                    contexts.add(context);
                    new Thread(() -> {
                        try {
                            start.await();
                            try (
                                    ShowPartitionsRecordCursorFactory factory = new ShowPartitionsRecordCursorFactory(tableToken);
                                    RecordCursor cursor0 = factory.getCursor(context);
                                    RecordCursor cursor1 = factory.getCursor(context)
                            ) {
                                for (int j = 0; j < 5; j++) {
                                    assertCursor(finallyExpected, false, true, false, cursor0, factory.getMetadata(), false);
                                    cursor0.toTop();
                                    assertCursor(finallyExpected, false, true, false, cursor1, factory.getMetadata(), false);
                                    cursor1.toTop();
                                }
                            }
                        } catch (Throwable err) {
                            err.printStackTrace();
                            Assert.fail();
                        } finally {
                            Path.clearThreadLocals();
                            completed.countDown();
                        }
                    }).start();
                }
                start.countDown();
                completed.await();
                dropTable(engine, compiler, defaultContext, tableName);
                for (int i = 0; i < numThreads; i++) {
                    contexts.get(i).close();
                }
                contexts.clear();
            }
        });
    }

    private static String testTableName(String tableName) {
        int idx = tableName.indexOf('[');
        return idx > 0 ? tableName.substring(0, idx) : tableName;
    }

    private TableToken createPopulateTable(
            CairoConfiguration cairoConfig,
            CairoEngine engine,
            SqlCompiler compiler,
            SqlExecutionContext context,
            String tableName
    ) throws Exception {
        String createTable = "CREATE TABLE " + tableName + '(' +
                "  investmentMill LONG," +
                "  ticketThous INT," +
                "  broker SYMBOL," +
                "  ts TIMESTAMP" +
                ") TIMESTAMP(ts) PARTITION BY DAY";
        if (isWal) {
            createTable += " WAL";
        }
        try (OperationFuture create = compiler.compile(createTable, context).execute(null)) {
            create.await();
        }
        try (
                TableModel tableModel = new TableModel(cairoConfig, tableName, PartitionBy.DAY)
                        .col("investmentMill", ColumnType.LONG)
                        .col("ticketThous", ColumnType.INT)
                        .col("broker", ColumnType.SYMBOL).symbolCapacity(32)
                        .timestamp("ts")
        ) {
            String insertStmt = insertFromSelectPopulateTableStmt(tableModel, 1000000, firstPartitionName, partitionCount);
            try (OperationFuture insert = compiler.compile(insertStmt, context).execute(null)) {
                insert.await();
            }
        }
        if (isWal) {
            drainWalQueue(engine);
        }
        return engine.getTableToken(tableName);
    }

    private void dropTable(CairoEngine engine, SqlCompiler compiler, SqlExecutionContext context, String tableName) throws SqlException {
        try (OperationFuture drop = compiler.compile("DROP TABLE " + tableName, context).execute(null)) {
            drop.await();
        }
        if (isWal) {
            drainWalQueue(engine);
        }
    }

    static {
        // log is needed to greedily allocate logger infra and
        // exclude it from leak detector
        LogFactory.getLog(ServerMainShowPartitionsTest.class);
    }
}
