package com.owo.banking_ledger.common;

import java.time.Instant;

public record ErrorResponse(
        Instant timestamp,
        String code,
        String message) {

    public static ErrorResponse from(BusinessException exception) {
        return new ErrorResponse(
                Instant.now(),
                exception.getCode().name(),
                exception.getMessage());
    }

    public static ErrorResponse of(
            BusinessErrorCode code,
            String message) {
        return new ErrorResponse(
                Instant.now(),
                code.name(),
                message);
    }
}
