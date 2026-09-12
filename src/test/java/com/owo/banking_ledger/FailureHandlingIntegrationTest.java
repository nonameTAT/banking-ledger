package com.owo.banking_ledger;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.observability.DatabaseFailure;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferService;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Checks what the service does when the database misbehaves rather than when a
 * caller does.
 *
 * <p>These are deliberately driven against a real PostgreSQL rather than mocks:
 * the behaviour under test belongs to the driver, the pool and the database
 * between them, and a mock would only assert what this test already assumed.
 */
@SpringBootTest(properties =
        "spring.datasource.hikari.data-source-properties.ApplicationName="
                + FailureHandlingIntegrationTest.POOL)
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "failure-tests", authorities = "SCOPE_ledger:admin")
class FailureHandlingIntegrationTest {

    /**
     * Tags every connection this context opens, so that the kill below can find
     * this pool's connections and only this pool's.
     */
    static final String POOL = "failure-handling-tests";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private DataSource dataSource;

    /**
     * Throws away whatever the test left in the pool.
     *
     * <p>Killing this pool's connections leaves the other nineteen of them dead
     * in the pool, not just the one the recovering test goes on to replace.
     * Hikari skips its aliveness check for a connection borrowed again within
     * half a second of being returned, so the next test to take several
     * connections at once takes dead ones, and fails when the transaction it
     * has already begun cannot be rolled back. That test then reads as a
     * contention or idempotency bug in a class that never touched a connection.
     *
     * <p>This runs after every test rather than only the killing ones: the
     * assertion that the pool recovers has already been made by the time it
     * runs, so evicting here weakens nothing, and a test added later that kills
     * connections is covered without having to remember this.
     */
    @AfterEach
    void discardConnectionsThisTestMayHaveKilled() throws SQLException {
        dataSource.unwrap(HikariDataSource.class)
                .getHikariPoolMXBean()
                .softEvictConnections();
    }

    // ----------------------------------------------------------- timeouts ---

    /**
     * The backstop is configured on the connection, not in application code, so
     * the only honest way to check it is to ask the database what it is running
     * with.
     */
    @Test
    void everyConnectionCarriesTheStatementTimeoutBackstop() {
        String timeout = jdbcTemplate.queryForObject(
                "SHOW statement_timeout",
                String.class);

        assertNotNull(timeout);
        assertTrue(
                !"0".equals(timeout),
                "statement_timeout must not be unlimited, was: " + timeout);
    }

    /**
     * A query that overruns has to be cut off and reported, not left to hold a
     * request thread until something else gives up.
     */
    @Test
    void aQueryThatOverrunsItsTimeoutIsCutOff() {
        assertThrows(QueryTimeoutException.class, () -> transactionTemplate
                .execute(status -> {
                    // Well under statement_timeout, but past the per-statement
                    // timeout set for this one query.
                    jdbcTemplate.execute("SET LOCAL statement_timeout = 250");

                    return jdbcTemplate.queryForObject(
                            "SELECT pg_sleep(5)",
                            String.class);
                }));
    }

    /**
     * A timeout has to be recognisable as datastore trouble, or it is answered
     * as an ordinary server error and never reaches the alert built for it.
     */
    @Test
    void aTimeoutIsClassifiedAsDatastoreTrouble() {
        QueryTimeoutException thrown = assertThrows(
                QueryTimeoutException.class,
                () -> transactionTemplate.execute(status -> {
                    jdbcTemplate.execute("SET LOCAL statement_timeout = 250");

                    return jdbcTemplate.queryForObject(
                            "SELECT pg_sleep(5)",
                            String.class);
                }));

        assertEquals(DatabaseFailure.TIMEOUT, DatabaseFailure.classify(thrown));
    }

    // -------------------------------------------------- lost connections ----

    /**
     * Connections die for reasons outside this service: a failover, an idle
     * reaper, an administrator. What matters is that the pool notices and the
     * next request is served, rather than handing out a dead connection.
     */
    @Test
    void theServiceKeepsWorkingAfterItsConnectionsAreKilled() {
        Long accountId = createAccount("Before connection loss");

        terminateOtherBackends();

        // No retry, no warm-up: the very next call has to work.
        AccountResponse account = accountService.findById(accountId);

        assertEquals(accountId, account.id());
    }

    @Test
    void moneyStillMovesCorrectlyAfterConnectionsAreKilled() {
        Long accountId = createAccount("Deposit after connection loss");

        terminateOtherBackends();
        deposit(accountId, "100.00");

        assertEquals(
                0,
                new java.math.BigDecimal("100.00").compareTo(
                        accountRepository.findById(accountId)
                                .orElseThrow()
                                .getBalance()));
    }

    // -------------------------------------------------------- contention ---

    /**
     * Concurrent deposits by the account's own owner.
     *
     * <p>Every other concurrency test in this suite runs as an administrator,
     * and an administrator skips the ownership check entirely. That check is
     * the only thing on this path that reads the account before the balance
     * code locks it, so the contended customer path was never actually
     * exercised: it failed under load with optimistic locking errors while the
     * tests stayed green. Running this as the owner is the whole point.
     */
    @Test
    @WithMockUser(username = "contention-owner")
    void concurrentDepositsByTheAccountOwnerAllSucceed() throws Exception {
        Long accountId = createAccount("Contention owner");
        int concurrency = 8;

        List<Callable<Void>> deposits = java.util.stream.IntStream.range(0, concurrency)
                .mapToObj(index -> (Callable<Void>) () -> {
                    deposit(accountId, "10.00");
                    return null;
                })
                .toList();

        List<Throwable> failures = runConcurrently(deposits);

        assertTrue(
                failures.isEmpty(),
                "deposits by the account owner should not fail under contention: "
                        + failures);

        // Every deposit has to be in the balance, not merely absent from the
        // failures: a lost update would leave this short without anything
        // throwing.
        assertEquals(
                0,
                new java.math.BigDecimal("80.00").compareTo(
                        accountRepository.findById(accountId)
                                .orElseThrow()
                                .getBalance()));
    }

    /**
     * The same contention through a transfer, where two accounts are locked
     * rather than one.
     */
    @Test
    @WithMockUser(username = "contention-owner")
    void concurrentTransfersByTheAccountOwnerAllSucceed() throws Exception {
        Long source = createAccount("Contention source");
        Long target = createAccount("Contention target");
        deposit(source, "500.00");

        String suffix = UUID.randomUUID().toString();
        List<Callable<Void>> transfers = java.util.stream.IntStream.range(0, 6)
                .mapToObj(index -> (Callable<Void>) () -> transfer(
                        source,
                        target,
                        "10.00",
                        "contention-transfer-" + index + "-" + suffix))
                .toList();

        List<Throwable> failures = runConcurrently(transfers);

        assertTrue(
                failures.isEmpty(),
                "transfers by the account owner should not fail under contention: "
                        + failures);
        assertEquals(
                0,
                new java.math.BigDecimal("60.00").compareTo(
                        accountRepository.findById(target)
                                .orElseThrow()
                                .getBalance()));
    }

    // ---------------------------------------------------------- deadlocks ---

    /**
     * Two transfers crossing in opposite directions are the textbook way to
     * deadlock a ledger. They do not here, because transfers always lock the
     * lower account id first, so the two orderings are really the same
     * ordering. This is the test that would fail if that rule were ever
     * dropped.
     */
    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        Long first = createAccount("Deadlock A");
        Long second = createAccount("Deadlock B");

        deposit(first, "500.00");
        deposit(second, "500.00");

        String suffix = UUID.randomUUID().toString();
        List<Throwable> failures = runConcurrently(List.of(
                () -> transfer(first, second, "10.00", "deadlock-ab-" + suffix),
                () -> transfer(second, first, "10.00", "deadlock-ba-" + suffix)));

        assertTrue(
                failures.isEmpty(),
                "opposing transfers should not fail: " + failures);

        // Both moved the same amount in opposite directions, so the balances
        // must be exactly where they started.
        assertEquals(
                0,
                new java.math.BigDecimal("500.00").compareTo(
                        accountRepository.findById(first).orElseThrow().getBalance()));
        assertEquals(
                0,
                new java.math.BigDecimal("500.00").compareTo(
                        accountRepository.findById(second).orElseThrow().getBalance()));
    }

    /**
     * When a deadlock is genuinely provoked, by locking in opposite orders
     * outside the service's rules, PostgreSQL breaks the cycle by killing one
     * side. That victim has to arrive as recognisable datastore trouble so it
     * is answered and counted as such.
     *
     * <p>The failure is captured where it is thrown rather than where it
     * surfaces. If a rollback fails too, Spring throws the rollback's exception
     * in place of the original and logs "Application exception overridden by
     * rollback exception"; what reaches the caller then says only that a
     * connection could not be rolled back, with the deadlock nowhere in it.
     * Asserting on that would be asserting on whether a second, unrelated
     * failure happened to occur.
     */
    @Test
    void aRealDeadlockIsBrokenAndRecognised() throws Exception {
        Long first = createAccount("Raw deadlock A");
        Long second = createAccount("Raw deadlock B");

        List<Throwable> victims = runConcurrentlyCollecting(List.of(
                () -> lockInOrderCapturingFailure(first, second),
                () -> lockInOrderCapturingFailure(second, first)));

        assertEquals(
                1,
                victims.size(),
                "exactly one side should be chosen as the deadlock victim");

        Throwable victim = victims.get(0);
        DatabaseFailure classified = DatabaseFailure.classify(victim);

        // The operational requirement, and the one that holds however the
        // failure surfaces: the victim is datastore trouble, so it is answered
        // as unavailable and counted, rather than escaping as an untracked
        // server error.
        assertNotNull(
                classified,
                "deadlock victim was not recognised as datastore trouble: "
                        + victim.getClass().getName());

        // Which category it lands in is not always knowable. When the rollback
        // fails too, Spring throws that instead of the original and the
        // deadlock is gone from the exception entirely, leaving only "unable to
        // rollback against JDBC connection". Nothing can recover LOCK from
        // that, so it is asserted for the shape that still carries the cause
        // and the other is pinned as the documented gap it is.
        if (isRollbackMasked(victim)) {
            assertEquals(
                    DatabaseFailure.OTHER,
                    classified,
                    "a masked rollback carries no cause, so it can only be OTHER");
        } else {
            assertEquals(
                    DatabaseFailure.LOCK,
                    classified,
                    "deadlock victim was classified as " + classified
                            + " from " + victim.getClass().getSimpleName());
        }
    }

    /**
     * Whether Spring replaced the original failure with one from the rollback,
     * which it logs as "Application exception overridden by rollback exception".
     */
    private static boolean isRollbackMasked(Throwable victim) {
        String message = String.valueOf(victim.getMessage());

        return message.contains("rollback");
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * Locks two accounts in the given order inside one transaction, pausing in
     * between so the opposing task has time to take the lock it needs. That
     * pause is what turns two orderings into a cycle.
     */
    /**
     * Locks two accounts in the given order inside one transaction, pausing in
     * between so the opposing task has time to take the lock it needs. That
     * pause is what turns two orderings into a cycle.
     *
     * @return what the second lock attempt threw, or {@code null} if it
     *         succeeded and this side was not the victim
     */
    private Throwable lockInOrderCapturingFailure(Long firstId, Long secondId) {
        java.util.concurrent.atomic.AtomicReference<Throwable> captured =
                new java.util.concurrent.atomic.AtomicReference<>();

        try {
            transactionTemplate.execute(status -> {
                // Locks through the repository the application itself uses, so
                // the deadlock surfaces from the same layer it would in
                // production rather than from a hand-written statement.
                accountRepository.findByIdForUpdate(firstId).orElseThrow();

                jdbcTemplate.queryForObject("SELECT pg_sleep(1)", String.class);

                try {
                    accountRepository.findByIdForUpdate(secondId).orElseThrow();
                } catch (RuntimeException deadlockVictim) {
                    captured.set(deadlockVictim);
                    status.setRollbackOnly();
                }

                return null;
            });
        } catch (RuntimeException rollbackFailure) {
            // Only reached if the rollback itself failed. The deadlock, if
            // there was one, is already captured above; this is a fallback so a
            // failure is never silently dropped.
            captured.compareAndSet(null, rollbackFailure);
        }

        return captured.get();
    }

    /**
     * Kills this pool's other connections, which is what the pool sees when a
     * database restarts or a failover happens.
     *
     * <p>Scoped to this context's own connections by {@code application_name},
     * and that scope is the whole point. The Testcontainers database is shared
     * with every other test class, and a class configured differently enough to
     * get its own Spring context gets its own pool against that same database.
     * An unscoped {@code pg_terminate_backend} reaches into those pools too, and
     * the test that fails is whichever one next borrows a connection that was
     * killed underneath it — a contention or idempotency test, in another class,
     * with nothing wrong with it.
     */
    private void terminateOtherBackends() {
        // If the tag were ever to stop being applied, the statement below would
        // match nothing and quietly stop testing anything at all.
        assertEquals(
                POOL,
                jdbcTemplate.queryForObject(
                        "SELECT current_setting('application_name')",
                        String.class),
                "this pool's connections must be tagged for the kill to find them");

        jdbcTemplate.queryForList("""
                SELECT pg_terminate_backend(pid)
                FROM pg_stat_activity
                WHERE datname = current_database()
                    AND pid <> pg_backend_pid()
                    AND application_name = ?
                """, Boolean.class, POOL);
    }

    /**
     * Runs the tasks at once and returns the failures they reported, as
     * opposed to the ones they threw.
     */
    private static List<Throwable> runConcurrentlyCollecting(
            List<Callable<Throwable>> tasks) throws Exception {
        ExecutorService executor = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(tasks.size()));

        try {
            List<Future<Throwable>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            List<Throwable> reported = new java.util.ArrayList<>();

            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(30, TimeUnit.SECONDS);

                if (failure != null) {
                    reported.add(failure);
                }
            }

            return reported;
        } finally {
            executor.shutdownNow();
        }
    }

    /** Runs the tasks at once and returns whatever they threw. */
    private static List<Throwable> runConcurrently(
            List<Callable<Void>> tasks) throws Exception {
        ExecutorService executor = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(tasks.size()));

        try {
            List<Future<Void>> futures = tasks.stream()
                    .map(executor::submit)
                    .toList();

            List<Throwable> failures = new java.util.ArrayList<>();

            for (Future<Void> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }

            return failures;
        } finally {
            executor.shutdownNow();
        }
    }

    private Long createAccount(String ownerName) {
        return accountService.create(
                new CreateAccountRequest(ownerName, "AUD")).id();
    }

    private void deposit(Long accountId, String amount) {
        depositService.deposit(accountId, new DepositRequest(
                new java.math.BigDecimal(amount),
                "AUD",
                "failure-deposit-" + UUID.randomUUID(),
                "Failure handling test"));
    }

    private Void transfer(
            Long sourceId,
            Long targetId,
            String amount,
            String referenceId) {
        transferService.transfer(new TransferRequest(
                sourceId,
                targetId,
                new java.math.BigDecimal(amount),
                "AUD",
                referenceId,
                "Failure handling test"));

        return null;
    }
}
