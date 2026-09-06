package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogRepository;
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositResponse;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.ReversalNotAllowedException;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;
import com.owo.banking_ledger.reversal.ReversalRequest;
import com.owo.banking_ledger.reversal.ReversalResponse;
import com.owo.banking_ledger.reversal.ReversalService;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferResponse;
import com.owo.banking_ledger.transfer.TransferService;
import com.owo.banking_ledger.withdrawal.WithdrawalRequest;
import com.owo.banking_ledger.withdrawal.WithdrawalService;

/**
 * A reversal corrects a posted transaction by adding new mirrored entries. The
 * original transaction and its entries stay exactly as they were posted.
 */
@SpringBootTest
class ReversalIntegrationTest {

    private static final int THREADS = 4;

    // Ledger entries are append-only, so posted test data is never deleted.
    // Each test creates its own accounts and unique reference ids instead.

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private ReversalService reversalService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void reversingADepositMirrorsItsEntriesAndLinksTheOriginal() {
        Long accountId = createAccount("Reversal Deposit Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "reversal-deposit-" + suffix,
                        "Deposit to reverse"));

        ReversalResponse reversal = reversalService.reverse(
                deposit.transactionId(),
                new ReversalRequest("reverse-" + suffix, "Duplicate deposit"));

        assertEquals(deposit.transactionId(), reversal.originalTransactionId());
        assertEquals(TransactionStatus.COMPLETED, reversal.status());
        assertBigDecimalEquals(new BigDecimal("100.00"), reversal.amount());
        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(accountId));

        LedgerTransaction original = transaction(deposit.transactionId());
        LedgerTransaction posted = transaction(reversal.transactionId());

        assertEquals(TransactionStatus.REVERSED, original.getStatus());
        assertEquals(TransactionType.REVERSAL, posted.getTransactionType());
        assertEquals(original.getId(), posted.getReversalOf().getId());

        // The original entries survive untouched; the correction is new rows.
        List<LedgerEntry> originalEntries = entryRepository
                .findByTransactionId(original.getId());
        List<LedgerEntry> reversalEntries = entryRepository
                .findByTransactionId(posted.getId());

        assertEquals(2, originalEntries.size());
        assertEquals(2, reversalEntries.size());
        assertEquals(2, entryCountOf(accountId));
        assertEquals(
                EntryType.CREDIT,
                entryFor(originalEntries, accountId).getEntryType());
        assertEquals(
                EntryType.DEBIT,
                entryFor(reversalEntries, accountId).getEntryType());
        assertBigDecimalEquals(
                BigDecimal.ZERO,
                entryFor(reversalEntries, accountId).getBalanceAfter());
        assertTrue(auditedActions(accountId).contains(
                AuditAction.TRANSACTION_REVERSED));
    }

    @Test
    void reversingATransferMovesTheMoneyBack() {
        Long sourceAccountId = createAccount("Reversal Transfer Source");
        Long targetAccountId = createAccount("Reversal Transfer Target");
        String suffix = randomSuffix();

        depositService.deposit(
                sourceAccountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "reversal-transfer-funding-" + suffix,
                        "Transfer funding"));

        TransferResponse transfer = transferService.transfer(new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal("40.00"),
                "AUD",
                "reversal-transfer-" + suffix,
                "Transfer to reverse"));

        reversalService.reverse(
                transfer.transactionId(),
                new ReversalRequest("reverse-transfer-" + suffix, null));

        assertBigDecimalEquals(new BigDecimal("100.00"), balanceOf(sourceAccountId));
        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(targetAccountId));
        assertEquals(
                TransactionStatus.REVERSED,
                transaction(transfer.transactionId()).getStatus());
    }

    @Test
    void aTransactionCanOnlyBeReversedOnce() {
        Long accountId = createAccount("Double Reversal Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "double-reversal-deposit-" + suffix,
                        "Deposit to reverse"));

        reversalService.reverse(
                deposit.transactionId(),
                new ReversalRequest("double-reverse-a-" + suffix, null));

        ReversalNotAllowedException exception = assertThrows(
                ReversalNotAllowedException.class,
                () -> reversalService.reverse(
                        deposit.transactionId(),
                        new ReversalRequest("double-reverse-b-" + suffix, null)));

        assertTrue(exception.getMessage().startsWith(
                "Transaction has already been reversed"));
        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(accountId));
        assertEquals(2, entryCountOf(accountId), "only one reversal may post");
    }

    @Test
    void aReversalCannotItselfBeReversed() {
        Long accountId = createAccount("Reversal Of Reversal Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "nested-reversal-deposit-" + suffix,
                        "Deposit to reverse"));

        ReversalResponse reversal = reversalService.reverse(
                deposit.transactionId(),
                new ReversalRequest("nested-reverse-" + suffix, null));

        ReversalNotAllowedException exception = assertThrows(
                ReversalNotAllowedException.class,
                () -> reversalService.reverse(
                        reversal.transactionId(),
                        new ReversalRequest("nested-reverse-again-" + suffix, null)));

        assertTrue(exception.getMessage().startsWith(
                "A reversal cannot itself be reversed"));
    }

    @Test
    void repeatingAReversalRequestReplaysTheOriginalReversal() {
        Long accountId = createAccount("Idempotent Reversal Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "idempotent-reversal-deposit-" + suffix,
                        "Deposit to reverse"));

        ReversalRequest request = new ReversalRequest(
                "idempotent-reverse-" + suffix,
                "Duplicate deposit");

        ReversalResponse first = reversalService.reverse(
                deposit.transactionId(),
                request);
        ReversalResponse replay = reversalService.reverse(
                deposit.transactionId(),
                request);

        assertEquals(first.transactionId(), replay.transactionId());
        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(accountId));
        assertEquals(2, entryCountOf(accountId), "the reversal must post once");
    }

    @Test
    void reversalIsRejectedWhenTheMoneyIsNoLongerThere() {
        Long accountId = createAccount("Spent Funds Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "spent-deposit-" + suffix,
                        "Deposit to reverse"));

        withdrawalService.withdraw(
                accountId,
                new WithdrawalRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "spent-withdrawal-" + suffix,
                        "Spend the deposit"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> reversalService.reverse(
                        deposit.transactionId(),
                        new ReversalRequest("spent-reverse-" + suffix, null)));

        assertEquals("Insufficient balance", exception.getMessage());
        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(accountId));
        assertEquals(
                TransactionStatus.COMPLETED,
                transaction(deposit.transactionId()).getStatus());
        assertEquals(2, entryCountOf(accountId), "a failed reversal posts nothing");
    }

    @Test
    void concurrentReversalsOfTheSameTransactionPostOnce() throws Exception {
        Long accountId = createAccount("Concurrent Reversal Customer");
        String suffix = randomSuffix();

        DepositResponse deposit = depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal("100.00"),
                        "AUD",
                        "concurrent-reversal-deposit-" + suffix,
                        "Deposit to reverse"));

        List<Object> outcomes = runConcurrently(THREADS, index -> {
            try {
                return reversalService.reverse(
                        deposit.transactionId(),
                        new ReversalRequest(
                                "concurrent-reverse-" + suffix + "-" + index,
                                null));
            } catch (RuntimeException exception) {
                return exception;
            }
        });

        List<ReversalResponse> posted = outcomes.stream()
                .filter(ReversalResponse.class::isInstance)
                .map(ReversalResponse.class::cast)
                .toList();

        assertEquals(1, posted.size(), () -> "exactly one reversal may post: " + outcomes);
        outcomes.stream()
                .filter(outcome -> !(outcome instanceof ReversalResponse))
                .forEach(outcome -> assertInstanceOf(
                        ReversalNotAllowedException.class,
                        outcome));

        assertBigDecimalEquals(BigDecimal.ZERO, balanceOf(accountId));
        assertEquals(2, entryCountOf(accountId));
        assertEquals(
                TransactionStatus.REVERSED,
                transaction(deposit.transactionId()).getStatus());
    }

    private static LedgerEntry entryFor(List<LedgerEntry> entries, Long accountId) {
        return entries.stream()
                .filter(entry -> entry.getAccount().getId().equals(accountId))
                .findFirst()
                .orElseThrow();
    }

    private List<AuditAction> auditedActions(Long accountId) {
        return auditLogRepository
                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                        accountId,
                        accountId,
                        PageRequest.of(0, 20))
                .map(log -> log.getAction())
                .getContent();
    }

    private LedgerTransaction transaction(Long transactionId) {
        return transactionRepository.findById(transactionId).orElseThrow();
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
        return response.id();
    }

    private static List<Object> runConcurrently(
            int threadCount,
            IndexedTask task) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                int index = i;
                Callable<Object> callable = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call(index);
                };
                futures.add(executorService.submit(callable));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            List<Object> results = new ArrayList<>();

            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(15, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    results.add(exception.getCause());
                }
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

    @FunctionalInterface
    private interface IndexedTask {
        Object call(int index) throws Exception;
    }
}
