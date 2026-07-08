package com.owo.banking_ledger.audit;

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
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "related_account_id")
    private Long relatedAccountId;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "reference_id", length = 64)
    private String referenceId;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(length = 3)
    private String currency;

    @Column(length = 255)
    private String details;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {
    }

    public AuditLog(
            AuditAction action,
            Long accountId,
            Long relatedAccountId,
            Long transactionId,
            String referenceId,
            BigDecimal amount,
            String currency,
            String details) {
        this.action = action;
        this.accountId = accountId;
        this.relatedAccountId = relatedAccountId;
        this.transactionId = transactionId;
        this.referenceId = referenceId;
        this.amount = amount;
        this.currency = currency;
        this.details = details;
        this.createdAt = Instant.now();
    }
}
