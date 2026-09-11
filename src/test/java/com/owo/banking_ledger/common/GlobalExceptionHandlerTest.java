package com.owo.banking_ledger.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;

import com.owo.banking_ledger.observability.CurrentTrace;
import com.owo.banking_ledger.observability.DatabaseFailure;
import com.owo.banking_ledger.observability.LedgerMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * The handler is where a failure becomes countable, so these check the counting
 * as much as the response.
 */
class GlobalExceptionHandlerTest {

    private MeterRegistry registry;
    private LedgerMetrics metrics;
    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LedgerMetrics(registry);

        CurrentTrace currentTrace = Mockito.mock(CurrentTrace.class);
        Mockito.when(currentTrace.id()).thenReturn("trace-id");

        handler = new GlobalExceptionHandler(currentTrace, metrics);
    }

    /**
     * A database that cannot be reached fails before any statement runs, so it
     * arrives as a transaction exception rather than a data access one. Left
     * unhandled it would escape as an untracked server error, and the alert
     * built on the database counter would never fire during an outage.
     */
    @Test
    void anUnreachableDatabaseIsCountedAndReportedAsUnavailable() {
        ResponseEntity<ErrorResponse> response = handler.handleDatastoreFailure(
                new CannotCreateTransactionException("database is down"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(
                BusinessErrorCode.DATABASE_UNAVAILABLE.name(),
                response.getBody().code());
        assertEquals("trace-id", response.getBody().traceId());
        assertEquals(1.0, databaseFailures(DatabaseFailure.CONNECTION), 0.0001);
    }

    @Test
    void aFailedStatementIsCountedToo() {
        handler.handleDatastoreFailure(
                new DataAccessResourceFailureException("connection lost"));
        handler.handleDatastoreFailure(new QueryTimeoutException("too slow"));

        assertEquals(1.0, databaseFailures(DatabaseFailure.CONNECTION), 0.0001);
        assertEquals(1.0, databaseFailures(DatabaseFailure.TIMEOUT), 0.0001);
    }

    /**
     * A datastore outage must not also inflate the money movement signal, or
     * one incident would read as two unrelated problems.
     */
    @Test
    void aDatastoreOutageIsNotAlsoCountedAsATransactionError() {
        handler.handleDatastoreFailure(
                new CannotCreateTransactionException("database is down"));

        assertEquals(0.0, totalTransactionErrors(), 0.0001);
    }

    @Test
    void aRejectedRequestIsCountedAsATransactionError() {
        handler.handleBusinessException(
                BusinessException.invalidRequest("Insufficient balance"));

        assertEquals(1.0, totalTransactionErrors(), 0.0001);
    }

    @Test
    void authorizationRefusalsAreNotCountedAsTransactionErrors() {
        handler.handleBusinessException(new BusinessException(
                BusinessErrorCode.ACCESS_DENIED,
                "not your account"));

        assertEquals(0.0, totalTransactionErrors(), 0.0001);
    }

    private double databaseFailures(DatabaseFailure failure) {
        return registry.find(LedgerMetrics.DATABASE_FAILURES)
                .tag("reason", failure.tagValue())
                .counter()
                .count();
    }

    private double totalTransactionErrors() {
        return registry.find(LedgerMetrics.TRANSACTION_ERRORS)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }
}
