// Copyright Yahoo. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.testrunner;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static com.yahoo.vespa.testrunner.TestRunner.Status.ERROR;
import static com.yahoo.vespa.testrunner.TestRunner.Status.FAILURE;
import static com.yahoo.vespa.testrunner.TestRunner.Status.INCONCLUSIVE;
import static com.yahoo.vespa.testrunner.TestRunner.Status.NOT_STARTED;
import static com.yahoo.vespa.testrunner.TestRunner.Status.NO_TESTS;
import static com.yahoo.vespa.testrunner.TestRunner.Status.RUNNING;
import static com.yahoo.vespa.testrunner.TestRunner.Status.SUCCESS;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author jonmv
 */
class AggregateTestRunnerTest {

    @Test
    void onlySupportedRunnersAreUsed() {
        MockTestRunner unsupported = new MockTestRunner();
        unsupported.status = NO_TESTS;
        MockTestRunner supported = new MockTestRunner();
        supported.status = SUCCESS;
        TestRunner runner = AggregateTestRunner.of(List.of(unsupported, supported));
        CompletableFuture<?> future = runner.test(null, null);
        assertFalse(future.isDone());
        assertNotNull(unsupported.future);
        assertNull(supported.future);
        unsupported.future.complete(null);
        assertNotNull(supported.future);
        supported.future.complete(null);
        assertTrue(future.isDone());
    }

    @Test
    void noTestsResultInFailure() {
        TestRunner runner = AggregateTestRunner.of(List.of());
        runner.test(null, null);
        assertSame(NO_TESTS, runner.getStatus());
    }

    @Test
    void chainedRunners() {
        LogRecord record1 = new LogRecord(Level.INFO, "one");
        LogRecord record2 = new LogRecord(Level.INFO, "two");
        MockTestRunner first = new MockTestRunner();
        MockTestRunner second = new MockTestRunner();
        TestRunner runner = AggregateTestRunner.of(List.of(first, second));
        assertSame(NOT_STARTED, runner.getStatus());
        assertEquals(List.of(), runner.getLog(-1));

        // First wrapped runner is started.
        CompletableFuture<?> future = runner.test(null, null);
        assertNotNull(first.future);
        assertNull(second.future);
        assertEquals(RUNNING, runner.getStatus());

        // Logs from first wrapped runner are returned.
        assertEquals(List.of(), runner.getLog(-1));
        first.log.add(record1);
        assertEquals(List.of(record1), runner.getLog(-1));
        assertEquals(List.of(), runner.getLog(record1.getSequenceNumber()));

        // First wrapped runner completes, second is started.
        first.status = NO_TESTS;
        first.future.complete(null);
        assertNotNull(second.future);
        assertFalse(future.isDone());
        assertEquals(RUNNING, runner.getStatus());

        // Logs from second runner are available.
        second.log.add(record2);
        assertEquals(List.of(record1, record2), runner.getLog(-1));

        // No tests in any runner means no tests.
        second.status = NO_TESTS;
        second.future.complete(null);
        assertEquals(NO_TESTS, runner.getStatus());

        // No failures, and at least one success, mean success.
        second.status = SUCCESS;
        assertEquals(SUCCESS, runner.getStatus());

        // An inconclusive test means inconclusive.
        first.status = INCONCLUSIVE;
        assertEquals(INCONCLUSIVE, runner.getStatus());

        // A failure means failure.
        second.status = FAILURE;
        assertEquals(FAILURE, runner.getStatus());

        // An error means error.
        first.status = ERROR;
        assertEquals(ERROR, runner.getStatus());

        // Runner is re-used. Ensure nothing from the second wrapped runner is visible.
        runner.test(null, null);
        assertFalse(first.future.isDone());
        assertTrue(second.future.isDone());
        assertEquals(List.of(record1), runner.getLog(-1));
        assertEquals(RUNNING, runner.getStatus());

        // First wrapped runner completes exceptionally, but the second should be started as usual.
        first.future.completeExceptionally(new RuntimeException("error"));
        assertFalse(second.future.isDone());
        assertEquals(List.of(record1, record2), runner.getLog(-1));

        // Verify reports are merged.
        assertNull(runner.getReport());

        TestReport.Failure failure = new TestReport.Failure("test", null);
        TestReport report = TestReport.builder()
                                      .withLogs(List.of(record1))
                                      .withFailures(List.of(failure))
                                      .withSuccessCount(8)
                                      .withIgnoredCount(4)
                                      .withFailedCount(2)
                                      .withAbortedCount(1)
                                      .build();
        first.report = report;
        assertSame(report, runner.getReport());

        second.report = report;
        TestReport merged = runner.getReport();
        assertEquals(List.of(record1, record1), merged.logLines);
        assertEquals(List.of(failure, failure), merged.failures);
        assertEquals(16, merged.successCount);
        assertEquals(8, merged.ignoredCount);
        assertEquals(4, merged.failedCount);
        assertEquals(2, merged.abortedCount);
    }

    @Test
    void testReportStatus() {
        assertEquals(NO_TESTS, TestReport.builder().build().status());
        assertEquals(SUCCESS, TestReport.builder().withSuccessCount(1).build().status());
        assertEquals(INCONCLUSIVE, TestReport.builder().withSuccessCount(1).withInconclusiveCount(1).build().status());
        assertEquals(FAILURE, TestReport.builder().withSuccessCount(1).withFailedCount(1).build().status());
        assertEquals(SUCCESS, TestReport.builder().withAbortedCount(1).build().status());
        assertEquals(SUCCESS, TestReport.builder().withIgnoredCount(1).build().status());
        assertEquals(FAILURE, JunitRunner.createReportWithFailedInitialization(new RuntimeException("hello")).status());
    }

    static class MockTestRunner implements TestRunner {

        final List<LogRecord> log = new ArrayList<>();
        CompletableFuture<?> future;
        Status status = NOT_STARTED;
        TestReport report;

        @Override
        public Collection<LogRecord> getLog(long after) {
            return log.stream().filter(record -> record.getSequenceNumber() > after).collect(toList());
        }

        @Override
        public Status getStatus() {
            return status;
        }

        @Override
        public CompletableFuture<?> test(Suite suite, byte[] config) {
            return future = new CompletableFuture<>();
        }

        @Override
        public TestReport getReport() {
            return report;
        }

    }

}
