package com.owo.banking_ledger.common;

import org.springframework.http.HttpStatus;

public enum BusinessErrorCode {
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND),
    DUPLICATE_TRANSACTION(HttpStatus.CONFLICT),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT),
    IDEMPOTENCY_PAYLOAD_MISMATCH(HttpStatus.CONFLICT),
    REVERSAL_NOT_ALLOWED(HttpStatus.CONFLICT),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(HttpStatus.FORBIDDEN),
    DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE);

    private final HttpStatus status;

    BusinessErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    /**
     * Whether this code means money movement failed, as opposed to a caller
     * being turned away or the datastore being unwell.
     *
     * <p>Used both to decide what to count and to pre-register the counters at
     * startup, so the two can never disagree about which codes exist.
     */
    public boolean isLedgerFailure() {
        return switch (this) {
            // Refusals are the authorization layer working, not the ledger
            // failing, and counting them would bury real faults.
            case UNAUTHENTICATED, ACCESS_DENIED -> false;

            // Counted as a database failure instead, so one outage does not
            // register twice under two different names.
            case DATABASE_UNAVAILABLE -> false;

            default -> true;
        };
    }
}
