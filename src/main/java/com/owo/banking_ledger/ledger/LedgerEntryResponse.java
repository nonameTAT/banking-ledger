package com.owo.banking_ledger.ledger;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        Long transactionId,
        String referenceId,
        TransactionType transactionType,
        EntryType entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        Instant createdAt) {
    public static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getTransaction().getId(),
                entry.getTransaction().getReferenceId(),
                entry.getTransaction().getTransactionType(),
                entry.getEntryType(),
                entry.getAmount(),
                entry.getBalanceAfter(),
                entry.getCreatedAt());
    }
}
