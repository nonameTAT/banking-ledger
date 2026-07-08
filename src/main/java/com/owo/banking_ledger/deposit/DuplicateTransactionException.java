package com.owo.banking_ledger.deposit;

public class DuplicateTransactionException extends RuntimeException {

    public DuplicateTransactionException(String referenceId) {
        super("Transaction reference already exists: " + referenceId);
    }
}
