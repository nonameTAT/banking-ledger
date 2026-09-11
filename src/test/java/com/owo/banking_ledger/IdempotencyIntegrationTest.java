package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.springframework.context.annotation.Import;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.audit.AuditLogRepository;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositResponse;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.IdempotencyConflictException;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferResponse;
import com.owo.banking_ledger.transfer.TransferService;

/**
 * Covers idempotent replay: an identical retry returns the original result, a
 * reused reference id carrying a different payload is rejected, and concurrent
 * duplicates post exactly once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@WithMockUser(username = "integration-tests", authorities = "SCOPE_ledger:admin")
@AutoConfigureMockMvc
class IdempotencyIntegrationTest {

    private static final int THREADS = 6;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    private final List<Long> createdAccountIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> referenceIds = Collections.synchronizedList(new ArrayList<>());

    // Ledger entries are append-only, so posted test data is never deleted.
    // Each test creates its own accounts and unique reference ids instead.

    @Test
    void repeatedDepositReturnsTheOriginalResultAndPostsOnce() {
        Long accountId = createAccount("Idempotent Depositor");
        String referenceId = trackReference("idempotent-deposit-" + randomSuffix());

        DepositResponse first = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        referenceId,
                        "Idempotent deposit"));

        // Same payload, different scale: the amount is fingerprinted by value.
        DepositResponse replay = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.0000"),
                        "AUD",
                        referenceId,
                        "Idempotent deposit"));

        assertEquals(first.transactionId(), replay.transactionId());
        assertEquals(first.referenceId(), replay.referenceId());
        assertEquals(first.accountId(), replay.accountId());
        assertEquals(first.status(), replay.status());
        assertBigDecimalEquals(first.balanceAfter(), replay.balanceAfter());

        assertBigDecimalEquals(new BigDecimal("100.00"), balanceOf(accountId));
        assertEquals(1, entryCountOf(accountId), "the deposit must post once");
        assertEquals(2, entryRepository
                .findByTransactionId(first.transactionId())
                .size());
        assertEquals(1, auditLogCountFor(accountId, referenceId));
    }

    @Test
    void repeatedTransferReturnsTheOriginalBalancesOfBothAccounts() {
        Long sourceAccountId = createAccount("Idempotent Transfer Source");
        Long targetAccountId = createAccount("Idempotent Transfer Target");
        String suffix = randomSuffix();

        depositService.deposit(
                sourceAccountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        trackReference("idempotent-transfer-deposit-" + suffix),
                        "Transfer funding"));

        TransferRequest request = new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal("20.00"),
                "AUD",
                trackReference("idempotent-transfer-" + suffix),
                "Idempotent transfer");

        TransferResponse first = transferService.transfer(request);
        TransferResponse replay = transferService.transfer(request);

        assertEquals(first.transactionId(), replay.transactionId());
        assertBigDecimalEquals(
                first.sourceBalanceAfter(),
                replay.sourceBalanceAfter());
        assertBigDecimalEquals(
                first.targetBalanceAfter(),
                replay.targetBalanceAfter());

        assertBigDecimalEquals(new BigDecimal("80.00"), balanceOf(sourceAccountId));
        assertBigDecimalEquals(new BigDecimal("20.00"), balanceOf(targetAccountId));
        assertEquals(1, entryCountOf(targetAccountId), "the transfer must post once");
    }

    @Test
    void reusedReferenceIdWithADifferentPayloadIsRejected() throws Exception {
        Long accountId = createAccount("Idempotent Conflict Customer");
        String referenceId = trackReference("idempotent-conflict-" + randomSuffix());

        depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        referenceId,
                        "Original deposit"));

        assertThrows(
                IdempotencyConflictException.class,
                () -> depositService.deposit(
                        accountId,
                        new DepositRequest(
                                new BigDecimal("250.00"),
                                "AUD",
                                referenceId,
                                "Original deposit")));

        mockMvc.perform(post("/api/accounts/{accountId}/deposits", accountId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "100.00",
                                  "currency": "AUD",
                                  "referenceId": "%s",
                                  "description": "A different description"
                                }
                                """.formatted(referenceId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_MISMATCH"));

        assertBigDecimalEquals(new BigDecimal("100.00"), balanceOf(accountId));
        assertEquals(1, entryCountOf(accountId), "a rejected retry must not post");
    }

    @Test
    void concurrentDuplicateDepositsPostOnlyOnce() throws Exception {
        Long accountId = createAccount("Idempotent Concurrent Customer");
        String referenceId = trackReference("idempotent-concurrent-" + randomSuffix());

        List<DepositResponse> responses = runConcurrently(THREADS, () ->
                depositService.deposit(
                        accountId,
                        new DepositRequest(
                                new BigDecimal("100.00"),
                                "AUD",
                                referenceId,
                                "Concurrent duplicate")));

        Set<Long> transactionIds = responses.stream()
                .map(DepositResponse::transactionId)
                .collect(Collectors.toSet());

        assertEquals(THREADS, responses.size());
        assertEquals(1, transactionIds.size(),
                "every caller must see the same transaction");
        assertBigDecimalEquals(new BigDecimal("100.00"), balanceOf(accountId));
        assertEquals(1, entryCountOf(accountId), "the deposit must post once");
        assertEquals(2, entryRepository
                .findByTransactionId(transactionIds.iterator().next())
                .size());
        assertEquals(1, auditLogCountFor(accountId, referenceId));
    }

    private long auditLogCountFor(Long accountId, String referenceId) {
        return auditLogRepository
                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                        accountId,
                        accountId,
                        PageRequest.of(0, 50))
                .getContent()
                .stream()
                .filter(log -> referenceId.equals(log.getReferenceId()))
                .count();
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId)
                .map(Account::getBalance)
                .orElseThrow();
    }

    private long entryCountOf(Long accountId) {
        return entryRepository
                .findByAccountId(accountId, PageRequest.of(0, 1))
                .getTotalElements();
    }

    private Long createAccount(String ownerName) {
        AccountResponse response = accountService.create(
                new CreateAccountRequest(ownerName, "AUD"));
        createdAccountIds.add(response.id());
        return response.id();
    }

    private String trackReference(String referenceId) {
        referenceIds.add(referenceId);
        return referenceId;
    }

    private static <T> List<T> runConcurrently(
            int threadCount,
            Callable<T> task) throws Exception {
        // Authorization reads the security context, which is thread local.
        // Without this wrapper the worker threads would run unauthenticated.
        ExecutorService executorService = new DelegatingSecurityContextExecutorService(
                Executors.newFixedThreadPool(threadCount));
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<T>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(executorService.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call();
                }));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }

            return results;
        } finally {
            executorService.shutdownNow();
        }
    }

    private static void assertBigDecimalEquals(
            BigDecimal expected,
            BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }

    private static String randomSuffix() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }
}
