package com.owo.banking_ledger.reconciliation;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One pass over every account. Recorded even when nothing was wrong, so that
 * "no differences" can be told apart from "reconciliation stopped running".
 */
@Getter
@Entity
@Table(name = "reconciliation_runs")
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at", nullable = false)
    private Instant finishedAt;

    @Column(name = "accounts_checked", nullable = false)
    private int accountsChecked;

    @Column(name = "difference_count", nullable = false)
    private int differenceCount;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    protected ReconciliationRun() {
    }

    public ReconciliationRun(
            Instant startedAt,
            Instant finishedAt,
            int accountsChecked,
            int differenceCount,
            String traceId) {
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.accountsChecked = accountsChecked;
        this.differenceCount = differenceCount;
        this.traceId = traceId;
    }

    public boolean isClean() {
        return differenceCount == 0;
    }
}
