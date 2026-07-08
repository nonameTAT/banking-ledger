package com.owo.banking_ledger.common;

public class BusinessException extends RuntimeException {

    private final BusinessErrorCode code;

    public BusinessException(
            BusinessErrorCode code,
            String message) {
        super(message);
        this.code = code;
    }

    public BusinessErrorCode getCode() {
        return code;
    }

    public static BusinessException invalidRequest(String message) {
        return new BusinessException(BusinessErrorCode.INVALID_REQUEST, message);
    }
}
