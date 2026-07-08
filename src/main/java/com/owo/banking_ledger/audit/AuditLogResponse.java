package com.owo.banking_ledger.audit;

import java.math.BigDecimal;
import java.time.Instant;

public record AuditLogResponse(
        Long id,
        AuditAction action,
        Long accountId,
        Long relatedAccountId,
        Long transactionId,
        String referenceId,
        BigDecimal amount,
        String currency,
        String details,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog auditLog) {
        return new AuditLogResponse(
                auditLog.getId(),
                auditLog.getAction(),
                auditLog.getAccountId(),
                auditLog.getRelatedAccountId(),
                auditLog.getTransactionId(),
                auditLog.getReferenceId(),
                auditLog.getAmount(),
                auditLog.getCurrency(),
                auditLog.getDetails(),
                auditLog.getCreatedAt());
    }
}
