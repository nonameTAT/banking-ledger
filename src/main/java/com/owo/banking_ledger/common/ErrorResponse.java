package com.owo.banking_ledger.common;

import java.time.Instant;

/**
 * @param traceId id of the trace that produced this error, so a caller
 *                reporting a failure can quote something that leads straight to
 *                its log lines. Null only outside a traced request.
 */
public record ErrorResponse(
        Instant timestamp,
        String code,
        String message,
        String traceId) {

    public static ErrorResponse from(
            BusinessException exception,
            String traceId) {
        return new ErrorResponse(
                Instant.now(),
                exception.getCode().name(),
                exception.getMessage(),
                traceId);
    }

    public static ErrorResponse of(
            BusinessErrorCode code,
            String message,
            String traceId) {
        return new ErrorResponse(
                Instant.now(),
                code.name(),
                message,
                traceId);
    }
}
