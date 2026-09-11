package com.owo.banking_ledger.common;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.owo.banking_ledger.observability.CurrentTrace;
import com.owo.banking_ledger.observability.LedgerMetrics;

/**
 * Turns exceptions into the API's error shape, and is also where failures
 * become countable. Every error a caller sees passes through here, which makes
 * it the one place that can record them without scattering counters through the
 * services.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private final CurrentTrace currentTrace;
    private final LedgerMetrics metrics;

    public GlobalExceptionHandler(
            CurrentTrace currentTrace,
            LedgerMetrics metrics) {
        this.currentTrace = currentTrace;
        this.metrics = metrics;
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception) {
        // Authentication and authorization refusals are not money movement
        // going wrong, so they are left out of the transaction error signal
        // that alerting watches.
        if (isLedgerFailure(exception.getCode())) {
            metrics.recordTransactionError(exception.getCode().name());
        }

        return ResponseEntity
                .status(exception.getCode().status())
                .body(ErrorResponse.from(exception, currentTrace.id()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        metrics.recordTransactionError(
                BusinessErrorCode.DATA_INTEGRITY_VIOLATION.name());

        return respond(
                BusinessErrorCode.DATA_INTEGRITY_VIOLATION,
                "Request conflicts with existing data");
    }

    /**
     * Anything the datastore failed at that was not a constraint the caller
     * tripped: a lost connection, a lock timeout, a deadlock. These say the
     * service is unwell rather than that the request was wrong.
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccessFailure(
            DataAccessException exception) {
        metrics.recordDatabaseFailure(exception.getClass().getSimpleName());

        return respond(
                BusinessErrorCode.DATABASE_UNAVAILABLE,
                "The request could not be completed, please retry");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Request validation failed");

        return respond(BusinessErrorCode.INVALID_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception) {
        return respond(BusinessErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException exception) {
        return respond(BusinessErrorCode.INVALID_REQUEST, exception.getMessage());
    }

    private ResponseEntity<ErrorResponse> respond(
            BusinessErrorCode code,
            String message) {
        return ResponseEntity
                .status(code.status())
                .body(ErrorResponse.of(code, message, currentTrace.id()));
    }

    private static boolean isLedgerFailure(BusinessErrorCode code) {
        return switch (code) {
            case UNAUTHENTICATED, ACCESS_DENIED -> false;
            default -> true;
        };
    }
}
