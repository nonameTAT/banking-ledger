package com.owo.banking_ledger.reconciliation;

import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.observability.DatabaseFailure;
import com.owo.banking_ledger.observability.LedgerMetrics;

/**
 * Checks that the balances the service serves still match the entries behind
 * them.
 *
 * <p>Ledger entries are the source of truth and {@code Account.balance} is a
 * materialized figure kept for fast, lock-safe reads. Those two can only drift
 * through a defect, so a difference is a correctness alarm rather than
 * something to be corrected automatically: this reports, and never writes a
 * balance back.
 *
 * <p>The comparison itself runs in {@link ReconciliationRunner}, in its own
 * transaction. Everything here happens outside it, because metrics and logs are
 * claims about what durably happened and must not be made until the commit has
 * gone through.
 */
@Service
public class ReconciliationService {

    private static final Log logger = LogFactory.getLog(ReconciliationService.class);

    private final ReconciliationRunner runner;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDifferenceRepository differenceRepository;
    private final LedgerMetrics metrics;

    public ReconciliationService(
            ReconciliationRunner runner,
            ReconciliationRunRepository runRepository,
            ReconciliationDifferenceRepository differenceRepository,
            LedgerMetrics metrics) {
        this.runner = runner;
        this.runRepository = runRepository;
        this.differenceRepository = differenceRepository;
        this.metrics = metrics;
    }

    /**
     * Compares every account and stores what it found.
     *
     * <p>Deliberately not {@code @Transactional}. The run commits inside
     * {@link ReconciliationRunner#execute()}, and only once that call has
     * returned is it true that a check happened. Recording success from inside
     * the transaction would be a lie waiting to be told: a commit that then
     * failed, or a rollback, would leave no run in the database while the
     * in-memory success timestamp had already moved, and that timestamp is what
     * holds off the alert for reconciliation having stopped. The service would
     * go unchecked and look healthy doing it.
     *
     * @return the stored run, whose {@code differenceCount} is zero when the
     *         ledger agrees with itself
     */
    public ReconciliationRun reconcile() {
        ReconciliationOutcome outcome;

        try {
            outcome = runner.execute();
        } catch (RuntimeException exception) {
            // Counted here rather than in each caller so that a scheduled run
            // and an operator-triggered one are both accounted for, and exactly
            // once.
            metrics.recordReconciliationFailure(
                    DatabaseFailure.classify(exception));

            throw exception;
        }

        if (!outcome.differingAccountIds().isEmpty()) {
            // Logged at error level with the account ids, so the alert that
            // fires on the metric has something to land on in the logs. Only
            // after the commit, so what is logged is what can be queried.
            logger.error("Reconciliation found "
                    + outcome.differingAccountIds().size()
                    + " account(s) whose balance disagrees with their ledger "
                    + "entries: " + outcome.differingAccountIds());
        }

        metrics.recordReconciliationRun(outcome.run().getDifferenceCount());

        return outcome.run();
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationRun> findRuns(Pageable pageable) {
        return runRepository.findAllByOrderByStartedAtDesc(pageable);
    }

    @Transactional(readOnly = true)
    public Page<ReconciliationDifference> findDifferences(
            Long accountId,
            Pageable pageable) {
        return accountId == null
                ? differenceRepository.findAllByOrderByDetectedAtDesc(pageable)
                : differenceRepository.findByAccountIdOrderByDetectedAtDesc(
                        accountId,
                        pageable);
    }

    @Transactional(readOnly = true)
    public Optional<ReconciliationRun> findLatestRun() {
        return runRepository.findFirstByOrderByStartedAtDesc();
    }
}
