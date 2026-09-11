package com.owo.banking_ledger.observability;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;

/**
 * Sorts datastore trouble into a small, fixed set of causes.
 *
 * <p>The categories are bounded on purpose. Tagging a metric with an exception
 * class name lets the datastore decide how many time series exist, which is how
 * a metrics backend gets overwhelmed; the exact class is written to the log
 * instead, where the request's trace id already leads.
 *
 * <p>Spring reports datastore trouble through two unrelated hierarchies.
 * {@link DataAccessException} covers a statement that failed, while
 * {@link TransactionException} covers never getting as far as running one,
 * which is what a caller sees when the database is simply unreachable. Anything
 * watching only the first would stay silent through an outage.
 */
public enum DatabaseFailure {

    /** The database could not be reached, or a connection could not be had. */
    CONNECTION,

    /** A statement or transaction ran out of time. */
    TIMEOUT,

    /** A lock could not be taken, including deadlocks. */
    LOCK,

    /** Datastore trouble that does not fit the categories above. */
    OTHER;

    /**
     * @return the category, or {@code null} if the throwable is not datastore
     *         trouble at all
     */
    public static DatabaseFailure classify(Throwable throwable) {
        if (throwable instanceof TransactionException) {
            return classifyTransactionFailure((TransactionException) throwable);
        }

        if (throwable instanceof DataAccessException) {
            return classifyAccessFailure((DataAccessException) throwable);
        }

        return null;
    }

    private static DatabaseFailure classifyTransactionFailure(
            TransactionException exception) {
        if (exception instanceof CannotCreateTransactionException) {
            return CONNECTION;
        }

        if (exception instanceof TransactionTimedOutException) {
            return TIMEOUT;
        }

        return OTHER;
    }

    private static DatabaseFailure classifyAccessFailure(
            DataAccessException exception) {
        if (exception instanceof DataAccessResourceFailureException) {
            return CONNECTION;
        }

        if (exception instanceof QueryTimeoutException) {
            return TIMEOUT;
        }

        // CannotAcquireLockException and the deadlock exception both sit under
        // the pessimistic locking failure type.
        if (exception instanceof CannotAcquireLockException
                || exception instanceof PessimisticLockingFailureException) {
            return LOCK;
        }

        return OTHER;
    }

    /** Lower case reads better as a metric tag value. */
    public String tagValue() {
        return name().toLowerCase();
    }
}
