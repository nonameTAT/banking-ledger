package com.owo.banking_ledger;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.owo.banking_ledger.observability.DatabaseFailure;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.time.Duration;

/**
 * What happens when the database is not merely slow but gone.
 *
 * <p>This runs against its own container rather than the one the rest of the
 * suite shares, because the test works by stopping the database, and doing that
 * to the shared instance would take every other test down with it.
 *
 * <p>The point is not that an outage produces an error, which is obvious, but
 * that it produces a <em>bounded</em> and <em>recognisable</em> one: bounded,
 * because a request that waits forever holds a thread and takes the rest of the
 * service down with it; recognisable, because an outage that is classified as
 * an ordinary error never reaches the alert written for it.
 */
class DatabaseOutageTest {

    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(3);

    @Test
    void anOutageFailsRequestsQuicklyAndRecognisably() {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
            config.setInitializationFailTimeout(-1);

            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
                TransactionTemplate transactionTemplate = new TransactionTemplate(
                        new DataSourceTransactionManager(dataSource));

                assertEquals(
                        1,
                        jdbcTemplate.queryForObject("SELECT 1", Integer.class),
                        "the database should be reachable before it is stopped");

                postgres.stop();

                // Bounded: opening a transaction has to give up near the
                // connection timeout, not sit on the thread indefinitely. The
                // allowance is generous so this does not turn into a flaky
                // timing test; what it rules out is waiting forever.
                TransactionException thrown = assertTimeoutPreemptively(
                        CONNECTION_TIMEOUT.plusSeconds(20),
                        () -> assertThrows(
                                TransactionException.class,
                                () -> transactionTemplate.execute(status ->
                                        jdbcTemplate.queryForObject(
                                                "SELECT 1",
                                                Integer.class))));

                // Recognisable: an unreachable database fails before any
                // statement runs, so it never appears as a DataAccessException
                // and would be invisible to anything watching only that.
                assertEquals(
                        DatabaseFailure.CONNECTION,
                        DatabaseFailure.classify(thrown),
                        "an outage was classified as "
                                + DatabaseFailure.classify(thrown)
                                + " from " + thrown.getClass().getSimpleName());
            }
        }
    }

    /**
     * The pool must hand back a working connection once the database returns,
     * without the application being restarted.
     */
    @Test
    void theServiceRecoversOnItsOwnWhenTheDatabaseComesBack() throws Exception {
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17")) {
            postgres.start();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
            config.setInitializationFailTimeout(-1);

            try (HikariDataSource dataSource = new HikariDataSource(config)) {
                JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
                jdbcTemplate.queryForObject("SELECT 1", Integer.class);

                // Killing every backend is what a failover looks like to the
                // pool: the connections it holds are dead but the database is
                // there.
                jdbcTemplate.execute("""
                        SELECT pg_terminate_backend(pid)
                        FROM pg_stat_activity
                        WHERE datname = current_database()
                            AND pid <> pg_backend_pid()
                        """);

                try (Connection connection = dataSource.getConnection()) {
                    assertNotNull(connection);
                    assertEquals(
                            1,
                            jdbcTemplate.queryForObject("SELECT 1", Integer.class),
                            "the pool should replace dead connections by itself");
                }
            }
        }
    }
}
