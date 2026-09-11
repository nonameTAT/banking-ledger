package com.owo.banking_ledger.observability;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * The metrics the running service is judged by.
 *
 * <p>Counters are registered lazily per tag combination rather than up front,
 * because the interesting tag values are error codes, and enumerating them here
 * would mean this class had to be edited every time one was added.
 */
@Component
public class LedgerMetrics {

    public static final String TRANSACTION_ERRORS = "banking.transaction.errors";
    public static final String DATABASE_FAILURES = "banking.database.failures";
    public static final String RECONCILIATION_RUNS = "banking.reconciliation.runs";
    public static final String RECONCILIATION_DIFFERENCES =
            "banking.reconciliation.differences";

    private final MeterRegistry registry;

    /**
     * Differences found by the most recent run. A gauge rather than a counter:
     * alerting wants to know whether the ledger disagrees with itself right
     * now, not how often it ever has.
     */
    private final AtomicInteger currentDifferences = new AtomicInteger();

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;

        registry.gauge(
                RECONCILIATION_DIFFERENCES,
                Tags.empty(),
                currentDifferences,
                AtomicInteger::doubleValue);
    }

    /**
     * A request to move money that did not succeed, tagged with the reason so
     * an ordinary rejection such as insufficient balance can be told apart from
     * a fault.
     */
    public void recordTransactionError(String reason) {
        Counter.builder(TRANSACTION_ERRORS)
                .description("Money movement requests that did not complete")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    /**
     * The database refused or failed to serve a request. Separate from a
     * transaction error because the cause is the datastore rather than
     * the instruction it was given.
     */
    public void recordDatabaseFailure(String reason) {
        Counter.builder(DATABASE_FAILURES)
                .description("Requests that failed because of a datastore error")
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void recordReconciliationRun(int differenceCount) {
        Counter.builder(RECONCILIATION_RUNS)
                .description("Completed reconciliation runs")
                .tag("outcome", differenceCount == 0 ? "clean" : "differences")
                .register(registry)
                .increment();

        currentDifferences.set(differenceCount);
    }

    public int currentDifferences() {
        return currentDifferences.get();
    }
}
