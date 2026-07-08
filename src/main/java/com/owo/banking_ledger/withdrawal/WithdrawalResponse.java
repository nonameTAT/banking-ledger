package com.owo.banking_ledger.withdrawal;

import java.math.BigDecimal;

import com.owo.banking_ledger.ledger.TransactionStatus;

public record WithdrawalResponse(
        Long transactionId,
        String referenceId,
        Long accountId,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        BigDecimal balanceAfter) {
}
