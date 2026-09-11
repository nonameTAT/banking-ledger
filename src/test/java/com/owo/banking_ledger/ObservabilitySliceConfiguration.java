package com.owo.banking_ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.owo.banking_ledger.observability.CurrentTrace;
import com.owo.banking_ledger.observability.LedgerMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;

/**
 * Supplies what {@code GlobalExceptionHandler} needs inside a {@code @WebMvcTest}
 * slice, which carries neither the metrics nor the tracing auto-configuration.
 *
 * <p>Real components over mocks: the counters genuinely increment, so a web
 * slice test can assert that a failed request was recorded. The tracer is the
 * no-op one, since a slice has no trace to report.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({ CurrentTrace.class, LedgerMetrics.class })
public class ObservabilitySliceConfiguration {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    Tracer tracer() {
        return Tracer.NOOP;
    }
}
