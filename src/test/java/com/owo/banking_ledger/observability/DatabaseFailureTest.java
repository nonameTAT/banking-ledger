package com.owo.banking_ledger.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;

class DatabaseFailureTest {

    /**
     * The case the whole classifier exists for. An unreachable database fails
     * when the transaction is opened, and that exception is not a
     * {@code DataAccessException}, so anything matching only on that hierarchy
     * stays silent through an outage.
     */
    @Test
    void anUnreachableDatabaseIsRecognisedThroughTheTransactionHierarchy() {
        assertEquals(
                DatabaseFailure.CONNECTION,
                DatabaseFailure.classify(
                        new CannotCreateTransactionException("database is down")));
    }

    @Test
    void aFailedStatementIsRecognisedThroughTheDataAccessHierarchy() {
        assertEquals(
                DatabaseFailure.CONNECTION,
                DatabaseFailure.classify(
                        new DataAccessResourceFailureException("connection lost")));
    }

    @Test
    void timeoutsAndLocksGetTheirOwnCategories() {
        assertEquals(
                DatabaseFailure.TIMEOUT,
                DatabaseFailure.classify(new QueryTimeoutException("too slow")));
        assertEquals(
                DatabaseFailure.TIMEOUT,
                DatabaseFailure.classify(
                        new TransactionTimedOutException("too slow")));
        assertEquals(
                DatabaseFailure.LOCK,
                DatabaseFailure.classify(new CannotAcquireLockException("locked")));
    }

    /**
     * Spring only translates into its specific exception types when it is the
     * one issuing the statement. The same failure raised underneath Hibernate
     * arrives uncategorised, which is how a real deadlock can look identical to
     * any other error. The SQLState is what the database actually said, so it
     * has to be enough on its own.
     */
    @Test
    void anUncategorisedFailureIsClassifiedFromWhatTheDatabaseReported() {
        assertEquals(
                DatabaseFailure.LOCK,
                DatabaseFailure.classify(uncategorised("40P01")),
                "deadlock_detected");
        assertEquals(
                DatabaseFailure.LOCK,
                DatabaseFailure.classify(uncategorised("55P03")),
                "lock_not_available");
        assertEquals(
                DatabaseFailure.LOCK,
                DatabaseFailure.classify(uncategorised("40001")),
                "serialization_failure");
        assertEquals(
                DatabaseFailure.TIMEOUT,
                DatabaseFailure.classify(uncategorised("57014")),
                "query_canceled, which is what statement_timeout raises");
        assertEquals(
                DatabaseFailure.CONNECTION,
                DatabaseFailure.classify(uncategorised("08006")),
                "connection_failure");
        assertEquals(
                DatabaseFailure.CONNECTION,
                DatabaseFailure.classify(uncategorised("57P01")),
                "admin_shutdown");
    }

    @Test
    void anUncategorisedFailureWithNothingRecognisableStaysOther() {
        assertEquals(
                DatabaseFailure.OTHER,
                DatabaseFailure.classify(uncategorised("XX000")));
    }

    /**
     * Cause chains come from other people's code and are not guaranteed to be
     * acyclic. Java rejects an exception that causes itself, but nothing stops
     * two from causing each other, and walking that pair would never end.
     */
    @Test
    void aCyclicCauseChainDoesNotHang() {
        SQLException first = new SQLException("first", (String) null);
        SQLException second = new SQLException("second", (String) null);
        first.initCause(second);
        second.initCause(first);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(
                DatabaseFailure.OTHER,
                DatabaseFailure.classify(
                        new UncategorizedSQLException("task", "SELECT 1", first))));
    }

    private static UncategorizedSQLException uncategorised(String sqlState) {
        return new UncategorizedSQLException(
                "task",
                "SELECT 1",
                new SQLException("failed", sqlState));
    }

    /**
     * A connection lost mid-transaction is reported as the rollback failing.
     * That wrapper describes the symptom: its own cause is a bare
     * "connection is closed" with no SQLState. The failure that actually
     * explains the outage is held separately as the application exception, and
     * classifying the wrapper alone would call an outage an ordinary error.
     */
    @Test
    void aRollbackFailureIsClassifiedFromWhatActuallyFailed() {
        TransactionSystemException rollbackFailed = new TransactionSystemException(
                "JDBC rollback failed",
                new SQLException("Connection is closed"));

        rollbackFailed.initApplicationException(
                new DataAccessResourceFailureException(
                        "An I/O error occurred while sending to the backend"));

        assertEquals(
                DatabaseFailure.CONNECTION,
                DatabaseFailure.classify(rollbackFailed));
    }

    @Test
    void aRollbackFailureWithNothingBehindItStaysOther() {
        assertEquals(
                DatabaseFailure.OTHER,
                DatabaseFailure.classify(new TransactionSystemException(
                        "JDBC rollback failed",
                        new SQLException("Connection is closed"))));
    }

    @Test
    void anythingThatIsNotDatastoreTroubleIsNotClassified() {
        assertNull(DatabaseFailure.classify(new IllegalStateException("unrelated")));
    }
}
