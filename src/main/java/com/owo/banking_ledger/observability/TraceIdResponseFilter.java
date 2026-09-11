package com.owo.banking_ledger.observability;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Returns the trace id of every request to its caller.
 *
 * <p>Without this the id exists only in the server's logs, so a client that
 * sees a failure has nothing to quote. The header is set before the chain
 * continues, because a committed response can no longer take headers, and that
 * is exactly what happens on the error paths where the id matters most.
 *
 * <p>The order puts this filter between two others and it has to stay there.
 * It runs after the observation filter, which starts the trace at
 * {@code HIGHEST_PRECEDENCE + 1}, so there is an id to read; and before Spring
 * Security, which answers rejected requests itself without calling the rest of
 * the chain. Ordering it later would leave exactly the unauthorized and
 * forbidden responses that most need an id without one.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class TraceIdResponseFilter extends OncePerRequestFilter {

    static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final CurrentTrace currentTrace;

    public TraceIdResponseFilter(CurrentTrace currentTrace) {
        this.currentTrace = currentTrace;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = currentTrace.id();

        if (traceId != null) {
            response.setHeader(TRACE_ID_HEADER, traceId);
        }

        filterChain.doFilter(request, response);
    }
}
