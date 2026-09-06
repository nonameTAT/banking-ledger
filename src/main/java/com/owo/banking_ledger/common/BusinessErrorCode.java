package com.owo.banking_ledger.common;

import org.springframework.http.HttpStatus;

public enum BusinessErrorCode {
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT),
    IDEMPOTENCY_PAYLOAD_MISMATCH(HttpStatus.CONFLICT),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    BusinessErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
