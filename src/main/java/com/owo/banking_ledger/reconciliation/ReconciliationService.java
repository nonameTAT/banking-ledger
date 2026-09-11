package com.owo.banking_ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.observability.CurrentTrace;
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
 */
@Service
public class ReconciliationService {

    private static final Log logger = LogFactory.getLog(ReconciliationService.class);

    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDifferenceRepository differenceRepository;
    private final LedgerMetrics metrics;
    private final CurrentTrace currentTrace;

    public ReconciliationService(
            ReconciliationRepository reconciliationRepository,
            ReconciliationRunRepository runRepository,
            ReconciliationDifferenceRepository differenceRepository,
            LedgerMetrics metrics,
            CurrentTrace currentTrace) {
        this.reconciliationRepository = reconciliationRepository;
        this.runRepository = runRepository;
        this.differenceRepository = differenceRepository;
        this.metrics = metrics;
        this.currentTrace = currentTrace;
    }

    /**
     * Compares every account and stores what it found.
     *
     * @return the stored run, whose {@code differenceCount} is zero when the
     *         ledger agrees with itself
     */
    @Transactional
    public ReconciliationRun reconcile() {
        Instant startedAt = Instant.now();
        List<AccountBalanceComparison> comparisons =
                reconciliationRepository.compareBalances();

        List<ReconciliationDifference> differences = new ArrayList<>();
        Instant detectedAt = Instant.now();

        for (AccountBalanceComparison comparison : comparisons) {
            BigDecimal recorded = comparison.getRecordedBalance();
            BigDecimal derived = comparison.getDerivedBalance();

            // compareTo, not equals: the two figures arrive with different
            // scales and 50.0000 must not be reported as differing from 50.00.
            if (recorded.compareTo(derived) != 0) {
                differences.add(new ReconciliationDifference(
                        null,
                        comparison.getAccountId(),
                        recorded,
                        derived,
                        detectedAt));
            }
        }

        ReconciliationRun run = runRepository.saveAndFlush(new ReconciliationRun(
                startedAt,
                Instant.now(),
                comparisons.size(),
                differences.size(),
                currentTrace.id()));

        if (!differences.isEmpty()) {
            differences.forEach(difference -> difference.assignRun(run.getId()));
            differenceRepository.saveAll(differences);

            // Logged at error level with the account ids, so the alert that
            // fires on the metric has something to land on in the logs.
            logger.error("Reconciliation found " + differences.size()
                    + " account(s) whose balance disagrees with their ledger "
                    + "entries: " + differences.stream()
                            .map(ReconciliationDifference::getAccountId)
                            .toList());
        }

        metrics.recordReconciliationRun(differences.size());

        return run;
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
    public java.util.Optional<ReconciliationRun> findLatestRun() {
        return runRepository.findFirstByOrderByStartedAtDesc();
    }
}
