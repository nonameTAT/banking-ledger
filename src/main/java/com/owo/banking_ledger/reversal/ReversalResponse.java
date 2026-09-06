package com.owo.banking_ledger.reversal;

import java.math.BigDecimal;

import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.TransactionStatus;

public record ReversalResponse(
        Long transactionId,
        String referenceId,
        Long originalTransactionId,
        String originalReferenceId,
        BigDecimal amount,
        String currency,
        TransactionStatus status) {

    public static ReversalResponse from(LedgerTransaction reversal) {
        LedgerTransaction original = reversal.getReversalOf();

        return new ReversalResponse(
                reversal.getId(),
                reversal.getReferenceId(),
                original.getId(),
                original.getReferenceId(),
                reversal.getAmount(),
                reversal.getCurrency(),
                reversal.getStatus());
    }
}
