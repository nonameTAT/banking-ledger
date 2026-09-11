package com.owo.banking_ledger.observability;

import org.springframework.stereotype.Component;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Reads the id of the trace the current request belongs to.
 *
 * <p>The same id appears in the request's log lines, in the {@code X-Trace-Id}
 * response header, and in the body of any error the request produces, so a
 * report of "this call failed" can be followed straight into the logs.
 */
@Component
public class CurrentTrace {

    private final Tracer tracer;

    public CurrentTrace(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * @return the current trace id, or {@code null} outside a trace, which is
     *         the case for work that no request started
     */
    public String id() {
        Span span = tracer.currentSpan();

        return span == null
                ? null
                : span.context().traceId();
    }
}
