package com.owo.banking_ledger.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.owo.banking_ledger.common.BusinessErrorCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Pins the property the alert rules depend on: a counter must already exist,
 * holding zero, before the thing it counts has ever happened.
 *
 * <p>Without it, {@code increase()} has no earlier sample to compare against
 * and the first occurrence, the one worth paging on, produces no alert.
 */
class LedgerMetricsTest {

    private MeterRegistry registry;
    private LedgerMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LedgerMetrics(registry);
    }

    @Test
    void everyDatabaseFailureCategoryIsPublishedAtZeroBeforeAnythingFails() {
        for (DatabaseFailure failure : DatabaseFailure.values()) {
            Counter counter = registry.find(LedgerMetrics.DATABASE_FAILURES)
                    .tag("reason", failure.tagValue())
                    .counter();

            assertNotNull(counter, "missing series for " + failure);
            assertEquals(0.0, counter.count(), 0.0001);
        }
    }

    @Test
    void everyLedgerErrorCodeIsPublishedAtZeroBeforeAnythingFails() {
        for (BusinessErrorCode code : BusinessErrorCode.values()) {
            Counter counter = registry.find(LedgerMetrics.TRANSACTION_ERRORS)
                    .tag("reason", code.name())
                    .counter();

            if (code.isLedgerFailure()) {
                assertNotNull(counter, "missing series for " + code);
                assertEquals(0.0, counter.count(), 0.0001);
            } else {
                // Refusals and datastore outages are counted elsewhere; giving
                // them a series here would invite alerting on the wrong thing.
                assertEquals(null, counter, "unexpected series for " + code);
            }
        }
    }

    @Test
    void reconciliationOutcomeAndFailureSeriesStartAtZero() {
        for (String outcome : new String[] { "clean", "differences" }) {
            assertEquals(
                    0.0,
                    registry.find(LedgerMetrics.RECONCILIATION_RUNS)
                            .tag("outcome", outcome)
                            .counter()
                            .count(),
                    0.0001);
        }

        for (DatabaseFailure failure : DatabaseFailure.values()) {
            assertNotNull(registry.find(LedgerMetrics.RECONCILIATION_FAILURES)
                    .tag("cause", failure.tagValue())
                    .counter());
        }
    }

    @Test
    void theFirstFailureIsAnObservableStepUpFromZero() {
        Counter counter = registry.find(LedgerMetrics.DATABASE_FAILURES)
                .tag("reason", DatabaseFailure.CONNECTION.tagValue())
                .counter();

        assertEquals(0.0, counter.count(), 0.0001);

        metrics.recordDatabaseFailure(DatabaseFailure.CONNECTION);

        assertEquals(1.0, counter.count(), 0.0001);
    }

    /**
     * Zero reads as "never reconciled". An alert comparing it against the
     * current time then fires, which is the whole point: a service that has
     * never once checked its ledger must not look healthy.
     */
    @Test
    void lastSuccessStartsAtNeverAndIsSetByASuccessfulRun() {
        assertEquals(0L, metrics.lastSuccessEpochSeconds());

        metrics.recordReconciliationRun(0);

        assertTrue(metrics.lastSuccessEpochSeconds() > 0);
    }

    @Test
    void aFailedRunDoesNotCountAsASuccess() {
        metrics.recordReconciliationFailure(DatabaseFailure.CONNECTION);

        assertEquals(
                0L,
                metrics.lastSuccessEpochSeconds(),
                "a run that threw must not look like a successful check");
        assertEquals(
                1.0,
                registry.find(LedgerMetrics.RECONCILIATION_FAILURES)
                        .tag("cause", "connection")
                        .counter()
                        .count(),
                0.0001);
    }
}
