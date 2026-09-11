package com.owo.banking_ledger.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * An account whose stored balance disagrees with the balance its ledger entries
 * add up to. Both figures are kept, not just the gap, because which one is
 * wrong is the first question asked.
 */
@Getter
@Entity
@Table(name = "reconciliation_differences")
public class ReconciliationDifference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "recorded_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal recordedBalance;

    @Column(name = "derived_balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal derivedBalance;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal difference;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    protected ReconciliationDifference() {
    }

    public ReconciliationDifference(
            Long runId,
            Long accountId,
            BigDecimal recordedBalance,
            BigDecimal derivedBalance,
            Instant detectedAt) {
        this.runId = runId;
        this.accountId = accountId;
        this.recordedBalance = recordedBalance;
        this.derivedBalance = derivedBalance;
        this.difference = recordedBalance.subtract(derivedBalance);
        this.detectedAt = detectedAt;
    }

    /**
     * A run's id exists only once its row is written, so differences are built
     * first and attached afterwards.
     */
    void assignRun(Long runId) {
        this.runId = runId;
    }
}
