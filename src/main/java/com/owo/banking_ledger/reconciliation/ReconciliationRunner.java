package com.owo.banking_ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.observability.CurrentTrace;

/**
 * The transactional half of reconciliation: compare every account, store what
 * was found, and return it.
 *
 * <p>This is a separate bean purely to place the transaction boundary. Metrics
 * live in memory and do not roll back, so anything that reports success has to
 * run strictly after the commit, and a method cannot wait for its own
 * transaction to commit from inside itself. Splitting the work out means the
 * commit happens as this call returns, which is what lets
 * {@link ReconciliationService} record the outcome only once it is durable, and
 * makes a commit failure arrive there as an ordinary exception.
 */
@Component
class ReconciliationRunner {

    private final ReconciliationRepository reconciliationRepository;
    private final ReconciliationRunRepository runRepository;
    private final ReconciliationDifferenceRepository differenceRepository;
    private final CurrentTrace currentTrace;

    ReconciliationRunner(
            ReconciliationRepository reconciliationRepository,
            ReconciliationRunRepository runRepository,
            ReconciliationDifferenceRepository differenceRepository,
            CurrentTrace currentTrace) {
        this.reconciliationRepository = reconciliationRepository;
        this.runRepository = runRepository;
        this.differenceRepository = differenceRepository;
        this.currentTrace = currentTrace;
    }

    /**
     * Allowed longer than a request transaction: this scans every account,
     * while the default timeout is sized for a single customer's operation.
     */
    @Transactional(timeoutString = "${banking.reconciliation.transaction-timeout:60}")
    ReconciliationOutcome execute() {
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
        }

        return new ReconciliationOutcome(
                run,
                differences.stream()
                        .map(ReconciliationDifference::getAccountId)
                        .toList());
    }
}
