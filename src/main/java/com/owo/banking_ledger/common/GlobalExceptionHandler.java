package com.owo.banking_ledger.common;

import java.time.Instant;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Map<String, Object> handleAccountNotFound(
            AccountNotFoundException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "code", "ACCOUNT_NOT_FOUND",
                "message", exception.getMessage());
    }

    @ExceptionHandler(DuplicateTransactionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDuplicateTransaction(
            DuplicateTransactionException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "code", "DUPLICATE_TRANSACTION",
                "message", exception.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "code", "DATA_INTEGRITY_VIOLATION",
                "message", "Request conflicts with existing data");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(
            IllegalArgumentException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "code", "INVALID_REQUEST",
                "message", exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalState(
            IllegalStateException exception) {
        return Map.of(
                "timestamp", Instant.now(),
                "code", "INVALID_REQUEST",
                "message", exception.getMessage());
    }
}
