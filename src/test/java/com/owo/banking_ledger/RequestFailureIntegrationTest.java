package com.owo.banking_ledger;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.owo.banking_ledger.observability.LedgerMetrics;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;

/**
 * What a caller gets, and what the ledger is left holding, when the database
 * fails in the middle of a real request.
 *
 * <p>{@link FailureHandlingIntegrationTest} and {@link DatabaseOutageTest} drive
 * the datastore directly: they establish that PostgreSQL reports timeouts,
 * deadlocks and outages, and that those arrive classified. Neither says anything
 * about a deposit or a transfer, because neither goes through one. Proving the
 * database throws is not the same as proving the service answers correctly and
 * leaves nothing half-posted, and the second is the property that matters to
 * whoever's money it is.
 *
 * <p>So every test here posts to the real endpoint, through the real security
 * filter chain, service, and exception handler, and then checks three things
 * that have to hold together:
 *
 * <ol>
 * <li>the response: {@code 503 DATABASE_UNAVAILABLE}, with a trace id, and
 *     counted as a datastore failure under the right cause;
 * <li>the rollback: balances, the transaction row, both ledger entries, and the
 *     audit row all absent, not merely most of them;
 * <li>the retry: posting the same {@code referenceId} again once the database is
 *     healthy lands the money exactly once, and again after that does not
 *     double-post it.
 * </ol>
 *
 * <p>The failure is injected with a trigger on the table the service is about to
 * write, raising the SQLSTATE PostgreSQL itself raises for a deadlock, or
 * cancelling the backend outright for a genuine query cancellation. The fault is
 * synthetic; everything it happens to — the request, the transaction boundary,
 * the rollback, the response, the counter — is real. Waiting for a natural
 * deadlock to land inside a chosen statement is not something a test can do
 * reliably, and injecting it is what makes the assertions above deterministic.
 */
@SpringBootTest(properties =
        "spring.datasource.hikari.data-source-properties.ApplicationName="
                + RequestFailureIntegrationTest.POOL)
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RequestFailureIntegrationTest {

    /**
     * Tags every connection this context opens, so that the kill below can find
     * this pool's connections and only this pool's.
     */
    static final String POOL = "request-failure-tests";

    /** PostgreSQL's deadlock_detected: what a broken lock cycle reports. */
    private static final String DEADLOCK = "40P01";

    /** serialization_failure: the other way a transaction loses a race. */
    private static final String SERIALIZATION_FAILURE = "40001";

    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** Tables a posting writes, in the order the services write them. */
    private static final String[] WRITTEN_TABLES = {
        "ledger_transactions", "ledger_entries", "audit_logs"
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void removeInjectedFailures() {
        clearInjectedFailure();
    }

    /**
     * Throws away whatever the test left in the pool.
     *
     * <p>Killing this pool's connections leaves the rest of them dead in the
     * pool, and Hikari skips its aliveness check for a connection borrowed again
     * within half a second of being returned — so the next test to take several
     * at once takes dead ones and fails for reasons of its own that are not its
     * own. See the same method on {@link FailureHandlingIntegrationTest}.
     */
    @AfterEach
    void discardConnectionsThisTestMayHaveKilled() throws SQLException {
        dataSource.unwrap(HikariDataSource.class)
                .getHikariPoolMXBean()
                .softEvictConnections();
    }

    // ------------------------------------------------------- the response ---

    /**
     * A deadlock while the entries are being written has to reach the caller as
     * the service being unwell, not as a rejected request and not as an
     * unexplained 500, and it has to be counted under the cause it actually
     * had.
     */
    @Test
    void aDepositThatDeadlocksIsAnsweredAsUnavailableAndCountedAsALock() throws Exception {
        Long accountId = openAccount();
        String referenceId = reference("deadlock");
        double before = databaseFailures("lock");

        failOn("ledger_entries", DEADLOCK);

        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                // The caller is told to retry, which is only honest because the
                // retry below is proven not to double-post.
                .andExpect(jsonPath("$.message").value(
                        "The request could not be completed, please retry"))
                // Without this the caller has nothing to quote when reporting
                // the failure, and the request is unfindable in the logs.
                .andExpect(jsonPath("$.traceId").exists());

        assertEquals(
                before + 1,
                databaseFailures("lock"),
                0.0001,
                "a deadlock must be counted as a lock failure, not as 'other'");
    }

    /**
     * A cancelled statement is the same shape as the statement timeout
     * configured on every connection: PostgreSQL reports both as
     * {@code 57014 query_canceled}. This is a real cancellation, issued by the
     * server against its own backend, rather than an error code chosen by the
     * test.
     */
    @Test
    void aDepositWhoseStatementIsCancelledIsCountedAsATimeout() throws Exception {
        Long accountId = openAccount();
        String referenceId = reference("cancelled");
        double before = databaseFailures("timeout");

        cancelOn("ledger_entries");

        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        assertEquals(
                before + 1,
                databaseFailures("timeout"),
                0.0001,
                "a cancelled statement must be counted as a timeout");

        assertNothingWasPosted(accountId, referenceId);
    }

    // ------------------------------------------------------- the rollback ---

    /**
     * The failure lands on the last write a deposit makes. By then the balances
     * have been changed, the transaction row is written and both entries are
     * pending, so this is the case with the most to undo and the most to get
     * wrong: an audit row that cannot be written must take the money movement
     * with it rather than leaving an unaudited posting behind.
     */
    @Test
    void aDepositThatFailsOnItsAuditRowRollsBackTheWholePosting() throws Exception {
        Long accountId = openAccount();
        String referenceId = reference("audit-failure");

        BigDecimal customerBefore = balanceOf(accountId);
        BigDecimal systemBefore = systemCashBalance();

        failOn("audit_logs", DEADLOCK);

        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isServiceUnavailable());

        assertNothingWasPosted(accountId, referenceId);
        assertBalance(customerBefore, accountId, "customer balance");
        // Double-entry means the cash account moved too, so rolling back only
        // the customer's side would leave the ledger unbalanced.
        assertEquals(
                0,
                systemBefore.compareTo(systemCashBalance()),
                "the system cash balance moved: was " + systemBefore
                        + ", now " + systemCashBalance());
    }

    /**
     * The transaction row is written first and flushed immediately, so this
     * fails before any balance changes. The reference id must be left unclaimed:
     * a failed posting that keeps its reference id would make the caller's
     * retry look like a duplicate forever.
     */
    @Test
    void aDepositThatFailsWritingItsTransactionLeavesTheReferenceIdFree()
            throws Exception {
        Long accountId = openAccount();
        String referenceId = reference("transaction-failure");

        failOn("ledger_transactions", SERIALIZATION_FAILURE);

        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isServiceUnavailable());

        assertNothingWasPosted(accountId, referenceId);

        clearInjectedFailure();

        // Not a 409: the first attempt posted nothing, so this is new work.
        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isCreated());

        assertBalance(AMOUNT, accountId, "balance after the retry");
    }

    /**
     * A transfer locks two accounts and moves money between them, so a failure
     * has to leave both sides exactly where they were. Checking only the source
     * would pass while the target kept money nobody sent.
     */
    @Test
    void aTransferThatFailsRollsBackBothAccounts() throws Exception {
        Long source = openAccount();
        Long target = openAccount();
        fund(source);

        BigDecimal sourceBefore = balanceOf(source);
        BigDecimal targetBefore = balanceOf(target);
        int sourceEntriesBefore = entriesFor(source);
        int targetEntriesBefore = entriesFor(target);

        String referenceId = reference("transfer-failure");
        failOn("ledger_entries", DEADLOCK);

        mockMvc.perform(transferRequest(source, target, referenceId))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        assertEquals(0, transactionsFor(referenceId), "the transaction was posted");
        assertEquals(0, auditRowsFor(referenceId), "an audit row survived");
        assertBalance(sourceBefore, source, "source balance");
        assertBalance(targetBefore, target, "target balance");
        assertEquals(
                sourceEntriesBefore,
                entriesFor(source),
                "an entry was left on the source account");
        assertEquals(
                targetEntriesBefore,
                entriesFor(target),
                "an entry was left on the target account");

        // And the retry moves the money once.
        clearInjectedFailure();

        mockMvc.perform(transferRequest(source, target, referenceId))
                .andExpect(status().isCreated());

        assertEquals(1, transactionsFor(referenceId));
        assertBalance(
                targetBefore.add(new BigDecimal("25.00")),
                target,
                "target balance after the retry");
    }

    // ---------------------------------------------------------- the retry ---

    /**
     * The whole point of answering a failure with "please retry".
     *
     * <p>Run as the account's own owner rather than as an administrator. An
     * administrator skips the ownership check, and that check is the only thing
     * on this path that reads the account before the balance code locks it, so
     * an administrator would not exercise the path a customer's retry takes.
     * The capacity work found a defect that hid in exactly that gap.
     */
    @Test
    void retryingAFailedDepositPostsItExactlyOnce() throws Exception {
        String owner = "retry-owner-" + UUID.randomUUID();
        Long accountId = openAccountOwnedBy(owner);
        String referenceId = reference("retry");

        failOn("ledger_entries", DEADLOCK);

        mockMvc.perform(depositRequest(accountId, referenceId, customer(owner)))
                .andExpect(status().isServiceUnavailable());

        assertNothingWasPosted(accountId, referenceId);

        clearInjectedFailure();

        String first = mockMvc.perform(
                        depositRequest(accountId, referenceId, customer(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // A caller that never saw the first response retries again. This is the
        // case that double-posts if the reference id is not honoured.
        String replay = mockMvc.perform(
                        depositRequest(accountId, referenceId, customer(owner)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertEquals(
                JsonPath.read(first, "$.transactionId").toString(),
                JsonPath.read(replay, "$.transactionId").toString(),
                "the replay returned a different transaction");

        assertBalance(AMOUNT, accountId, "balance after two retries");
        assertEquals(
                1,
                transactionsFor(referenceId),
                "the deposit was posted more than once");
        assertEquals(
                2,
                entriesForReference(referenceId),
                "a deposit must leave exactly one entry on each side");
        assertEquals(
                1,
                auditRowsFor(referenceId),
                "the deposit was audited more than once");
    }

    // ----------------------------------------------------- connection loss ---

    /**
     * Every pooled connection dying is what the service sees through a failover
     * or a database restart.
     *
     * <p>What is asserted is not that the next request succeeds. The pool can
     * hand out a connection that died before it had noticed, and a request
     * served on one has genuinely failed; answering it as unavailable is
     * correct. What must hold is the pair: the caller's retry is served, and the
     * two attempts together post the deposit once. A connection loss may cost a
     * retry; it may not cost a double posting.
     */
    @Test
    void aDepositAfterLosingEveryConnectionPostsExactlyOnce() throws Exception {
        Long accountId = openAccount();
        String referenceId = reference("connections-lost");

        terminateOtherBackends();

        // No warm-up query first: the deposit itself is the first thing to ask
        // the pool for a connection after the outage.
        int first = mockMvc.perform(depositRequest(accountId, referenceId))
                .andReturn()
                .getResponse()
                .getStatus();

        assertTrue(
                first == 201 || first == 503,
                "a deposit during a connection loss should either post or be"
                        + " answered as unavailable, was: " + first);

        mockMvc.perform(depositRequest(accountId, referenceId))
                .andExpect(status().isCreated());

        assertBalance(AMOUNT, accountId, "balance after the connection loss");
        assertEquals(
                1,
                transactionsFor(referenceId),
                "the deposit was posted more than once across the retry");
        assertEquals(
                1,
                auditRowsFor(referenceId),
                "the deposit should be audited exactly once");
    }

    // ------------------------------------------------- failure injection ----

    /**
     * Makes the next insert into {@code table} fail the way PostgreSQL does,
     * with a real SQLSTATE rather than a generic error, since the SQLSTATE is
     * what the service's classification reads.
     */
    private void failOn(String table, String sqlState) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_injected_failure()
                RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'injected failure on %%', TG_TABLE_NAME
                        USING ERRCODE = '%s';
                END;
                $$ LANGUAGE plpgsql
                """.formatted(sqlState));

        installTrigger(table);
    }

    /**
     * Has the backend cancel its own statement while inserting into
     * {@code table}, which PostgreSQL reports as {@code 57014 query_canceled} —
     * the same code a statement timeout produces.
     */
    private void cancelOn(String table) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION test_injected_failure()
                RETURNS TRIGGER AS $$
                BEGIN
                    PERFORM pg_cancel_backend(pg_backend_pid());

                    -- A cancellation is only noticed at the next point the
                    -- backend checks for interrupts, so this gives it one. It
                    -- is a bound, not a delay: the sleep is cut short.
                    PERFORM pg_sleep(5);

                    RETURN NEW;
                END;
                $$ LANGUAGE plpgsql
                """);

        installTrigger(table);
    }

    private void installTrigger(String table) {
        jdbcTemplate.execute(
                "DROP TRIGGER IF EXISTS trg_test_injected_failure ON " + table);

        jdbcTemplate.execute("""
                CREATE TRIGGER trg_test_injected_failure
                    BEFORE INSERT ON %s
                    FOR EACH ROW
                    EXECUTE FUNCTION test_injected_failure()
                """.formatted(table));
    }

    /**
     * Removes the injection from every table it could have been put on, rather
     * than from the one the test remembers using, so a test that failed partway
     * cannot leave the next one writing into a broken database.
     */
    private void clearInjectedFailure() {
        for (String table : WRITTEN_TABLES) {
            jdbcTemplate.execute(
                    "DROP TRIGGER IF EXISTS trg_test_injected_failure ON " + table);
        }

        jdbcTemplate.execute("DROP FUNCTION IF EXISTS test_injected_failure()");
    }

    /**
     * Kills this pool's connections except the one issuing the statement, which
     * is what the pool sees when the database restarts or fails over.
     *
     * <p>Deliberately not its own connection as well. The pool is shared with
     * every other test in this context, and leaving it with nothing live to hand
     * out makes the next class to ask for a connection fail for reasons that
     * have nothing to do with it.
     *
     * <p>Scoped to this context's own connections by {@code application_name}
     * for the same reason, one level out: the Testcontainers database is shared
     * with every other test class, and a class that gets its own Spring context
     * gets its own pool against that same database. Unscoped, this reaches into
     * those pools too.
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

    // ------------------------------------------------------- observations ---

    /**
     * Nothing a posting writes survived it: not the transaction, not the
     * entries, not the audit row. Asserted together because a rollback that
     * leaves any one of them behind is the failure worth catching.
     */
    private void assertNothingWasPosted(Long accountId, String referenceId) {
        assertEquals(
                0,
                transactionsFor(referenceId),
                "a transaction row survived the rollback");
        assertEquals(
                0,
                entriesForReference(referenceId),
                "a ledger entry survived the rollback");
        assertEquals(
                0,
                auditRowsFor(referenceId),
                "an audit row survived the rollback");
        assertBalance(
                BigDecimal.ZERO,
                accountId,
                "the balance moved for a posting that failed");
    }

    private void assertBalance(BigDecimal expected, Long accountId, String what) {
        BigDecimal actual = balanceOf(accountId);

        assertEquals(
                0,
                expected.compareTo(actual),
                what + ": expected " + expected + " but was " + actual);
    }

    private int transactionsFor(String referenceId) {
        return count(
                "SELECT count(*) FROM ledger_transactions WHERE reference_id = ?",
                referenceId);
    }

    private int entriesForReference(String referenceId) {
        return count("""
                SELECT count(*)
                FROM ledger_entries e
                JOIN ledger_transactions t ON t.id = e.transaction_id
                WHERE t.reference_id = ?
                """, referenceId);
    }

    private int entriesFor(Long accountId) {
        return count(
                "SELECT count(*) FROM ledger_entries WHERE account_id = ?",
                accountId);
    }

    private int auditRowsFor(String referenceId) {
        return count(
                "SELECT count(*) FROM audit_logs WHERE reference_id = ?",
                referenceId);
    }

    private int count(String sql, Object argument) {
        Integer total = jdbcTemplate.queryForObject(sql, Integer.class, argument);

        assertNotNull(total);

        return total;
    }

    private BigDecimal balanceOf(Long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class,
                accountId);
    }

    private BigDecimal systemCashBalance() {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE account_number = ?",
                BigDecimal.class,
                "SYSTEM-CASH-AUD");
    }

    private double databaseFailures(String reason) {
        return Search.in(meterRegistry)
                .name(LedgerMetrics.DATABASE_FAILURES)
                .tag("reason", reason)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    // ----------------------------------------------------------- requests ---

    private MockHttpServletRequestBuilder depositRequest(
            Long accountId,
            String referenceId) {
        return depositRequest(accountId, referenceId, admin());
    }

    private MockHttpServletRequestBuilder depositRequest(
            Long accountId,
            String referenceId,
            RequestPostProcessor caller) {
        return post("/api/accounts/{id}/deposits", accountId)
                .with(caller)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "amount": "%s",
                          "currency": "AUD",
                          "referenceId": "%s",
                          "description": "Request failure test"
                        }
                        """.formatted(AMOUNT, referenceId));
    }

    private MockHttpServletRequestBuilder transferRequest(
            Long source,
            Long target,
            String referenceId) {
        return post("/api/transfers")
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "sourceAccountId": %d,
                          "targetAccountId": %d,
                          "amount": "25.00",
                          "currency": "AUD",
                          "referenceId": "%s",
                          "description": "Request failure test"
                        }
                        """.formatted(source, target, referenceId));
    }

    private void fund(Long accountId) throws Exception {
        mockMvc.perform(depositRequest(accountId, reference("funding")))
                .andExpect(status().isCreated());
    }

    private Long openAccount() throws Exception {
        return openAccount(admin());
    }

    /**
     * Opens an account belonging to {@code subject}: an account is owned by
     * whoever opened it, so the token that creates it decides who may post to
     * it afterwards.
     */
    private Long openAccountOwnedBy(String subject) throws Exception {
        return openAccount(customer(subject));
    }

    private Long openAccount(RequestPostProcessor caller) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .with(caller)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Request failure",
                                  "currency": "AUD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long accountId = ((Number) JsonPath.read(body, "$.id")).longValue();

        assertTrue(accountId > 0, "unexpected account id: " + accountId);

        return accountId;
    }

    /**
     * A reference id is capped at 64 characters, and a request carrying a longer
     * one is rejected as invalid before it reaches the database at all, which
     * would quietly turn every test here into a test of validation.
     */
    private static String reference(String kind) {
        String referenceId = "rf-" + kind + "-"
                + UUID.randomUUID().toString().substring(0, 12);

        assertTrue(
                referenceId.length() <= 64,
                "reference id is too long to post: " + referenceId);

        return referenceId;
    }

    private static RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("request-failure-admin"))
                .authorities(new SimpleGrantedAuthority("SCOPE_ledger:admin"));
    }

    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject));
    }
}
