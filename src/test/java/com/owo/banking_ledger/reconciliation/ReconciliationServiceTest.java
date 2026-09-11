package com.owo.banking_ledger.reconciliation;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.UnexpectedRollbackException;

import com.owo.banking_ledger.observability.LedgerMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Metrics live in memory and do not roll back, so a run may only be reported as
 * successful once its transaction has committed.
 *
 * <p>The runner is a separate bean precisely so the commit happens as its call
 * returns. That makes a commit failure arrive here as an ordinary exception,
 * which is what these tests reproduce: an {@link UnexpectedRollbackException}
 * is what a caller actually sees when the work ran but the transaction was
 * rolled back at the end.
 */
class ReconciliationServiceTest {

    private MeterRegistry registry;
    private LedgerMetrics metrics;
    private ReconciliationRunner runner;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LedgerMetrics(registry);
        runner = Mockito.mock(ReconciliationRunner.class);

        service = new ReconciliationService(
                runner,
                Mockito.mock(ReconciliationRunRepository.class),
                Mockito.mock(ReconciliationDifferenceRepository.class),
                metrics);
    }

    /**
     * The case that matters most. The success timestamp is what holds off the
     * alert for reconciliation having stopped, so moving it for a run that was
     * rolled back would leave the ledger unchecked and looking healthy.
     */
    @Test
    void aRollbackAtCommitDoesNotLeaveASuccessRecorded() {
        Mockito.when(runner.execute())
                .thenThrow(new UnexpectedRollbackException("transaction rolled back"));

        assertThrows(UnexpectedRollbackException.class, () -> service.reconcile());

        assertEquals(
                0L,
                metrics.lastSuccessEpochSeconds(),
                "a run that did not commit must not move the last success time");
        assertEquals(
                0.0,
                completedRuns(),
                0.0001,
                "a run that did not commit must not count as completed");
    }

    @Test
    void aRollbackAtCommitDoesNotLeaveADifferenceCountBehind() {
        Mockito.when(runner.execute())
                .thenThrow(new UnexpectedRollbackException("transaction rolled back"));

        assertThrows(UnexpectedRollbackException.class, () -> service.reconcile());

        // The gauge would otherwise describe differences that were never
        // stored, so nothing could be queried to explain the alert.
        assertEquals(0, metrics.currentDifferences());
    }

    @Test
    void aFailedRunIsCountedOnceWhicheverCallerTriggeredIt() {
        Mockito.when(runner.execute())
                .thenThrow(new CannotCreateTransactionException("database is down"));

        assertThrows(CannotCreateTransactionException.class, () -> service.reconcile());

        assertEquals(1.0, reconciliationFailures("connection"), 0.0001);
    }

    @Test
    void aCommittedRunIsReportedAsSuccessful() {
        Mockito.when(runner.execute())
                .thenReturn(new ReconciliationOutcome(run(4, 0), List.of()));

        service.reconcile();

        assertEquals(1.0, completedRuns(), 0.0001);
        assertEquals(0, metrics.currentDifferences());
        org.junit.jupiter.api.Assertions.assertTrue(
                metrics.lastSuccessEpochSeconds() > 0);
    }

    @Test
    void aCommittedRunThatFoundDifferencesReportsThem() {
        Mockito.when(runner.execute())
                .thenReturn(new ReconciliationOutcome(run(4, 2), List.of(7L, 9L)));

        service.reconcile();

        assertEquals(2, metrics.currentDifferences());
        assertEquals(1.0, completedRuns(), 0.0001);
    }

    private static ReconciliationRun run(int accountsChecked, int differenceCount) {
        return new ReconciliationRun(
                Instant.now(),
                Instant.now(),
                accountsChecked,
                differenceCount,
                "trace-id");
    }

    private double completedRuns() {
        return registry.find(LedgerMetrics.RECONCILIATION_RUNS)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private double reconciliationFailures(String cause) {
        return registry.find(LedgerMetrics.RECONCILIATION_FAILURES)
                .tag("cause", cause)
                .counter()
                .count();
    }
}
