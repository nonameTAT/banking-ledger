package com.owo.banking_ledger.account;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(Long id) {
        super("Account not found: " + id);
    }
}
