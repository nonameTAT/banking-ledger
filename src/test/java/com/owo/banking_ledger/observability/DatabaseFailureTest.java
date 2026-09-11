package com.owo.banking_ledger.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.Test;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
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

    @Test
    void anythingThatIsNotDatastoreTroubleIsNotClassified() {
        assertNull(DatabaseFailure.classify(new IllegalStateException("unrelated")));
    }
}
