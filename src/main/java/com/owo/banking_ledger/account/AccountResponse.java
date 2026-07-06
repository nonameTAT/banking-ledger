package com.owo.banking_ledger.account;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        Long id,
        String accountNumber,
        String ownerName,
        String currency,
        AccountStatus status,
        BigDecimal balance,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getOwnerName(),
                account.getCurrency(),
                account.getStatus(),
                account.getBalance(),
                account.getCreatedAt());
    }
}
