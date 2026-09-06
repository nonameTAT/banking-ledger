package com.owo.banking_ledger.ledger;

import java.math.BigDecimal;
import java.time.Instant;

import com.owo.banking_ledger.common.BusinessException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reference_id", nullable = false, unique = true, length = 64)
    private String referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id")
    private LedgerTransaction reversalOf;

    protected LedgerTransaction() {
    }

    public LedgerTransaction(
            String referenceId,
            TransactionType transactionType,
            BigDecimal amount,
            String currency,
            String description,
            String requestHash) {
        this.referenceId = referenceId;
        this.transactionType = transactionType;
        this.status = TransactionStatus.PENDING;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.createdAt = Instant.now();
        this.requestHash = requestHash;
    }

    /**
     * Builds the transaction that reverses {@code original}, carrying the same
     * amount and currency and linked back to what it corrects.
     */
    public static LedgerTransaction reversing(
            LedgerTransaction original,
            String referenceId,
            String description,
            String requestHash) {
        LedgerTransaction reversal = new LedgerTransaction(
                referenceId,
                TransactionType.REVERSAL,
                original.getAmount(),
                original.getCurrency(),
                description,
                requestHash);

        reversal.reversalOf = original;

        return reversal;
    }

    public boolean isReversal() {
        return reversalOf != null;
    }

    public void markReversed() {
        if (status != TransactionStatus.COMPLETED) {
            throw new ReversalNotAllowedException(
                    "Only completed transactions can be reversed: "
                            + referenceId);
        }

        this.status = TransactionStatus.REVERSED;
    }

    public void complete() {
        if (status != TransactionStatus.PENDING) {
            throw BusinessException.invalidRequest(
                    "Only pending transactions can be completed");
        }

        this.status = TransactionStatus.COMPLETED;
    }

    public void fail() {
        if (status != TransactionStatus.PENDING) {
            throw BusinessException.invalidRequest(
                    "Only pending transactions can be failed");
        }

        this.status = TransactionStatus.FAILED;
    }
}
