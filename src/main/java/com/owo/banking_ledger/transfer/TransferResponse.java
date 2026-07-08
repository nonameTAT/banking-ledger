package com.owo.banking_ledger.transfer;

import java.math.BigDecimal;

import com.owo.banking_ledger.ledger.TransactionStatus;

public record TransferResponse(
        Long transactionId,
        String referenceId,
        Long sourceAccountId,
        Long targetAccountId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        BigDecimal sourceBalanceAfter,
        BigDecimal targetBalanceAfter) {
}
