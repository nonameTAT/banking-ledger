package com.owo.banking_ledger.observability;

import java.sql.SQLException;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
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
 *
 * <p>Exception type alone is not enough. Spring only translates into its
 * specific types when it is the one issuing the statement; the same failure
 * raised underneath Hibernate arrives as an uncategorised
 * {@code JpaSystemException}, which is how a real deadlock ends up looking
 * identical to any other error. The SQLState underneath says what actually
 * happened and is the same whichever layer raised it, so it is consulted
 * whenever the type is uninformative.
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
        DatabaseFailure fromType;

        if (throwable instanceof TransactionException transactionException) {
            fromType = classifyTransactionFailure(transactionException);
        } else if (throwable instanceof DataAccessException accessException) {
            fromType = classifyAccessFailure(accessException);
        } else {
            return null;
        }

        if (fromType != OTHER) {
            return fromType;
        }

        // The type was uninformative, which happens in both hierarchies: a
        // failure raised under Hibernate arrives uncategorised, and an outage
        // that kills a connection mid-transaction is reported as the rollback
        // failing rather than as the connection being gone. What the database
        // said is the same either way.
        DatabaseFailure fromDatabase = classifyBySqlState(throwable);

        return fromDatabase == null ? OTHER : fromDatabase;
    }

    private static DatabaseFailure classifyTransactionFailure(
            TransactionException exception) {
        if (exception instanceof CannotCreateTransactionException) {
            return CONNECTION;
        }

        if (exception instanceof TransactionTimedOutException) {
            return TIMEOUT;
        }

        // A connection lost mid-transaction is reported as the rollback
        // failing, and that wrapper describes the symptom: its own cause is a
        // bare "connection is closed" carrying no SQLState at all. What went
        // wrong is held separately, as the application exception, and only that
        // says the database became unreachable.
        if (exception instanceof TransactionSystemException systemException) {
            Throwable applicationException =
                    systemException.getApplicationException();

            if (applicationException != null) {
                DatabaseFailure fromApplication = classify(applicationException);

                if (fromApplication != null) {
                    return fromApplication;
                }
            }
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

    /**
     * Reads the cause the database itself reported.
     *
     * <p>Codes are from the SQL standard's class list as PostgreSQL implements
     * it. Class 08 is reserved for connection problems, so it is matched by
     * prefix rather than by listing codes that all mean the same thing.
     */
    private static DatabaseFailure classifyBySqlState(Throwable throwable) {
        String state = sqlState(throwable);

        if (state == null) {
            return null;
        }

        if (state.startsWith("08")) {
            return CONNECTION;
        }

        return switch (state) {
            // deadlock_detected, lock_not_available, serialization_failure:
            // all mean this transaction lost a race for a row.
            case "40P01", "55P03", "40001" -> LOCK;

            // query_canceled is what statement_timeout raises.
            case "57014" -> TIMEOUT;

            // The server is going away: shutting down, recovering, or not yet
            // accepting connections.
            case "57P01", "57P02", "57P03" -> CONNECTION;

            default -> null;
        };
    }

    /**
     * Cause chains are built by other people's code and are not guaranteed to
     * be acyclic, so the walk is bounded by depth rather than by looking for a
     * cycle: a depth limit catches a chain that loops back on itself however
     * long the loop is, and no real chain is anywhere near this deep.
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    private static String sqlState(Throwable throwable) {
        Throwable current = throwable;

        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof SQLException sqlException) {
                String state = sqlException.getSQLState();

                if (state != null && !state.isBlank()) {
                    return state;
                }
            }

            current = current.getCause();
        }

        return null;
    }

    /** Lower case reads better as a metric tag value. */
    public String tagValue() {
        return name().toLowerCase();
    }
}
