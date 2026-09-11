package com.owo.banking_ledger.reconciliation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.transaction.CannotCreateTransactionException;

import com.owo.banking_ledger.observability.DatabaseFailure;
import com.owo.banking_ledger.observability.LedgerMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The scheduled path never reaches the exception handler, so a run that throws
 * would otherwise leave nothing behind but a log line. That matters more than
 * it looks: if every run fails, the ledger is not being checked at all, and
 * every reconciliation alert would sit quiet because the success signal simply
 * never moves.
 */
class ReconciliationSchedulerTest {

    private MeterRegistry registry;
    private LedgerMetrics metrics;
    private ReconciliationService reconciliationService;
    private ReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LedgerMetrics(registry);
        reconciliationService = Mockito.mock(ReconciliationService.class);
        scheduler = new ReconciliationScheduler(reconciliationService, metrics);
    }

    @Test
    void aRunThatThrowsIsCountedAsAFailureAndAsADatabaseFailure() {
        Mockito.when(reconciliationService.reconcile())
                .thenThrow(new CannotCreateTransactionException("database is down"));

        scheduler.reconcile();

        assertEquals(1.0, reconciliationFailures("connection"), 0.0001);
        assertEquals(1.0, databaseFailures(DatabaseFailure.CONNECTION), 0.0001);
    }

    @Test
    void aFailedRunLeavesTheLastSuccessUntouched() {
        Mockito.when(reconciliationService.reconcile())
                .thenThrow(new CannotCreateTransactionException("database is down"));

        scheduler.reconcile();

        assertEquals(
                0L,
                metrics.lastSuccessEpochSeconds(),
                "a failed run must not look like the ledger was checked");
    }

    /**
     * The timer must survive a failure. If the exception escaped, Spring would
     * stop rescheduling and the service would quietly never check itself again.
     */
    @Test
    void theScheduleSurvivesAFailedRun() {
        Mockito.when(reconciliationService.reconcile())
                .thenThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> scheduler.reconcile());
    }

    @Test
    void aFailureThatIsNotDatastoreTroubleIsNotCountedAsADatabaseFailure() {
        Mockito.when(reconciliationService.reconcile())
                .thenThrow(new IllegalStateException("boom"));

        scheduler.reconcile();

        assertEquals(1.0, reconciliationFailures("other"), 0.0001);
        assertEquals(0.0, totalDatabaseFailures(), 0.0001);
    }

    private double reconciliationFailures(String cause) {
        return registry.find(LedgerMetrics.RECONCILIATION_FAILURES)
                .tag("cause", cause)
                .counter()
                .count();
    }

    private double databaseFailures(DatabaseFailure failure) {
        return registry.find(LedgerMetrics.DATABASE_FAILURES)
                .tag("reason", failure.tagValue())
                .counter()
                .count();
    }

    private double totalDatabaseFailures() {
        return registry.find(LedgerMetrics.DATABASE_FAILURES)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
