package com.owo.banking_ledger.observability;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.owo.banking_ledger.common.BusinessErrorCode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * The metrics the running service is judged by.
 *
 * <p>Every series is created at startup holding zero rather than appearing the
 * first time it is needed. A counter that springs into existence already at one
 * hides the very event it was meant to report: {@code increase()} and
 * {@code rate()} need two samples inside their window to return anything, so a
 * series born at one and left alone is indistinguishable from a series that was
 * always one. The first database failure, which is exactly the one worth
 * paging on, would produce no alert at all. Starting at zero makes the step to
 * one an observable change.
 *
 * <p>That is only affordable because every tag value is drawn from a fixed set,
 * which is also what keeps the number of series bounded.
 */
@Component
public class LedgerMetrics {

    public static final String TRANSACTION_ERRORS = "banking.transaction.errors";
    public static final String DATABASE_FAILURES = "banking.database.failures";
    public static final String RECONCILIATION_RUNS = "banking.reconciliation.runs";
    public static final String RECONCILIATION_FAILURES =
            "banking.reconciliation.failures";
    public static final String RECONCILIATION_DIFFERENCES =
            "banking.reconciliation.differences";
    public static final String RECONCILIATION_LAST_SUCCESS =
            "banking.reconciliation.last.success.timestamp";

    private final MeterRegistry registry;

    /**
     * Differences found by the most recent run. A gauge rather than a counter:
     * alerting wants to know whether the ledger disagrees with itself right
     * now, not how often it ever has.
     */
    private final AtomicInteger currentDifferences = new AtomicInteger();

    /**
     * When reconciliation last completed, as epoch seconds, so an alert can ask
     * how long it has been rather than counting runs in a window.
     *
     * <p>It starts at zero, which reads as "never". That is deliberate: a
     * service whose reconciliation has failed every time since it started is in
     * worse shape than one that has merely gone quiet, and an alert built on
     * counting runs would miss it entirely because the counter would never
     * appear.
     */
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong();

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;

        registry.gauge(
                RECONCILIATION_DIFFERENCES,
                Tags.empty(),
                currentDifferences,
                AtomicInteger::doubleValue);

        registry.gauge(
                RECONCILIATION_LAST_SUCCESS,
                Tags.empty(),
                lastSuccessEpochSeconds,
                AtomicLong::doubleValue);

        preRegisterCounters();
    }

    /**
     * A request to move money that did not succeed, tagged with the reason so
     * an ordinary rejection such as insufficient balance can be told apart from
     * a fault.
     */
    public void recordTransactionError(BusinessErrorCode code) {
        transactionErrors(code).increment();
    }

    /**
     * The datastore refused or failed to serve a request. Separate from a
     * transaction error because the cause is the database rather than the
     * instruction it was given.
     */
    public void recordDatabaseFailure(DatabaseFailure failure) {
        databaseFailures(failure).increment();
    }

    public void recordReconciliationRun(int differenceCount) {
        reconciliationRuns(differenceCount == 0).increment();

        currentDifferences.set(differenceCount);
        lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
    }

    /**
     * A run that threw instead of finishing. Counted separately from a run that
     * completed and found differences: one means the ledger is wrong, the other
     * means nobody is checking.
     */
    public void recordReconciliationFailure(DatabaseFailure cause) {
        Counter.builder(RECONCILIATION_FAILURES)
                .description("Reconciliation runs that did not complete")
                .tag("cause", cause == null ? "other" : cause.tagValue())
                .register(registry)
                .increment();
    }

    public int currentDifferences() {
        return currentDifferences.get();
    }

    public long lastSuccessEpochSeconds() {
        return lastSuccessEpochSeconds.get();
    }

    /**
     * Touches every series once so it exists, at zero, before anything has gone
     * wrong. Micrometer counters start at zero when registered, so this needs
     * no increment.
     */
    private void preRegisterCounters() {
        for (BusinessErrorCode code : BusinessErrorCode.values()) {
            if (code.isLedgerFailure()) {
                transactionErrors(code);
            }
        }

        for (DatabaseFailure failure : DatabaseFailure.values()) {
            databaseFailures(failure);
            recordReconciliationFailureSeries(failure);
        }

        reconciliationRuns(true);
        reconciliationRuns(false);
    }

    private Counter transactionErrors(BusinessErrorCode code) {
        return Counter.builder(TRANSACTION_ERRORS)
                .description("Money movement requests that did not complete")
                .tag("reason", code.name())
                .register(registry);
    }

    private Counter databaseFailures(DatabaseFailure failure) {
        return Counter.builder(DATABASE_FAILURES)
                .description("Requests that failed because of a datastore error")
                .tag("reason", failure.tagValue())
                .register(registry);
    }

    private Counter reconciliationRuns(boolean clean) {
        return Counter.builder(RECONCILIATION_RUNS)
                .description("Completed reconciliation runs")
                .tag("outcome", clean ? "clean" : "differences")
                .register(registry);
    }

    private Counter recordReconciliationFailureSeries(DatabaseFailure failure) {
        return Counter.builder(RECONCILIATION_FAILURES)
                .description("Reconciliation runs that did not complete")
                .tag("cause", failure.tagValue())
                .register(registry);
    }
}
