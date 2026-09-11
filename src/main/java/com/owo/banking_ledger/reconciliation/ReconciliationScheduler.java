package com.owo.banking_ledger.reconciliation;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.owo.banking_ledger.observability.DatabaseFailure;
import com.owo.banking_ledger.observability.LedgerMetrics;

/**
 * Runs reconciliation on a timer.
 *
 * <p>Kept apart from {@link ReconciliationService} so the check can be called
 * directly, by a test or by an operator through the API, without a scheduler
 * being involved.
 */
@Component
@ConditionalOnProperty(
        prefix = "banking.reconciliation",
        name = "scheduled",
        havingValue = "true",
        matchIfMissing = true)
public class ReconciliationScheduler {

    private static final Log logger =
            LogFactory.getLog(ReconciliationScheduler.class);

    private final ReconciliationService reconciliationService;
    private final LedgerMetrics metrics;

    public ReconciliationScheduler(
            ReconciliationService reconciliationService,
            LedgerMetrics metrics) {
        this.reconciliationService = reconciliationService;
        this.metrics = metrics;
    }

    /**
     * A failed run must not kill the timer, or the service would quietly stop
     * checking itself: the throwable is logged and the next run still happens.
     */
    @Scheduled(
            initialDelayString = "${banking.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${banking.reconciliation.interval:PT5M}")
    public void reconcile() {
        try {
            ReconciliationRun run = reconciliationService.reconcile();

            logger.info("Reconciliation checked " + run.getAccountsChecked()
                    + " account(s) and found " + run.getDifferenceCount()
                    + " difference(s)");
        } catch (RuntimeException exception) {
            // The failed run itself is already counted by the service, for
            // every caller. What is missing on this path is the datastore
            // failure: a scheduled run never reaches the exception handler that
            // records one for API requests, so an outage that only ever broke
            // reconciliation would otherwise go uncounted.
            DatabaseFailure cause = DatabaseFailure.classify(exception);

            if (cause != null) {
                metrics.recordDatabaseFailure(cause);
            }

            logger.error("Reconciliation run failed", exception);
        }
    }
}
