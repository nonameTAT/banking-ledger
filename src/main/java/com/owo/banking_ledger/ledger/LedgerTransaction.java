package com.owo.banking_ledger.ledger;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    protected LedgerTransaction() {
    }

    public LedgerTransaction(
            String referenceId,
            TransactionType transactionType,
            BigDecimal amount,
            String currency,
            String description) {
        this.referenceId = referenceId;
        this.transactionType = transactionType;
        this.status = TransactionStatus.PENDING;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.createdAt = Instant.now();
    }

    public void complete() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transactions can be completed");
        }

        this.status = TransactionStatus.COMPLETED;
    }

    public void fail() {
        if (status != TransactionStatus.PENDING) {
            throw new IllegalStateException(
                    "Only pending transactions can be failed");
        }

        this.status = TransactionStatus.FAILED;
    }
}
