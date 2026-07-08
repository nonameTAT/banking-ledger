package com.owo.banking_ledger.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(
            BusinessException exception) {
        return ResponseEntity
                .status(exception.getCode().status())
                .body(ErrorResponse.from(exception));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(
            DataIntegrityViolationException exception) {
        return ResponseEntity
                .status(BusinessErrorCode.DATA_INTEGRITY_VIOLATION.status())
                .body(ErrorResponse.of(
                        BusinessErrorCode.DATA_INTEGRITY_VIOLATION,
                        "Request conflicts with existing data"));
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

        return ResponseEntity
                .status(BusinessErrorCode.INVALID_REQUEST.status())
                .body(ErrorResponse.of(BusinessErrorCode.INVALID_REQUEST, message));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception) {
        return ResponseEntity
                .status(BusinessErrorCode.INVALID_REQUEST.status())
                .body(ErrorResponse.of(
                        BusinessErrorCode.INVALID_REQUEST,
                        exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException exception) {
        return ResponseEntity
                .status(BusinessErrorCode.INVALID_REQUEST.status())
                .body(ErrorResponse.of(
                        BusinessErrorCode.INVALID_REQUEST,
                        exception.getMessage()));
    }
}
