package com.owo.banking_ledger.security;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.ErrorResponse;
import com.owo.banking_ledger.observability.CurrentTrace;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Renders security failures in the same JSON shape as every other API error.
 *
 * <p>These two failures are raised by the filter chain before a request reaches
 * a controller, so {@code GlobalExceptionHandler} never sees them. Without this
 * writer a rejected request would answer with an empty body, and clients would
 * have to special-case it.
 */
@Component
public class ApiSecurityErrorWriter
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final CurrentTrace currentTrace;

    public ApiSecurityErrorWriter(
            ObjectMapper objectMapper,
            CurrentTrace currentTrace) {
        this.objectMapper = objectMapper;
        this.currentTrace = currentTrace;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(
                response,
                BusinessErrorCode.UNAUTHENTICATED,
                "A valid bearer token is required");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(
                response,
                BusinessErrorCode.ACCESS_DENIED,
                "Token does not permit this request");
    }

    private void write(
            HttpServletResponse response,
            BusinessErrorCode code,
            String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getWriter(),
                ErrorResponse.of(code, message, currentTrace.id()));
    }
}
