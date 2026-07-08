package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferService;
import com.owo.banking_ledger.withdrawal.WithdrawalRequest;
import com.owo.banking_ledger.withdrawal.WithdrawalService;

@SpringBootTest
class BankingConcurrencyIntegrationTest {

    private static final int THREADS = 10;

    @Autowired
    private AccountService accountService;

    @Autowired
    private DepositService depositService;

    @Autowired
    private WithdrawalService withdrawalService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    private final List<Long> createdAccountIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> referenceIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanUp() {
        List<LedgerTransaction> transactions = referenceIds.stream()
                .flatMap(referenceId -> transactionRepository
                        .findByReferenceId(referenceId)
                        .stream())
                .toList();

        transactions.forEach(transaction -> entryRepository.deleteAll(
                entryRepository.findByTransactionId(transaction.getId())));
        transactionRepository.deleteAll(transactions);
        accountRepository.deleteAllById(createdAccountIds);
    }

    @Test
    void concurrentWithdrawalsSerializeBalanceUpdates() throws Exception {
        Long accountId = createAccount("Concurrent Withdrawal Source");
        String suffix = randomSuffix();
        deposit(accountId, "concurrent-withdrawal-deposit-" + suffix, "100.00");

        runConcurrently(THREADS, index -> {
            withdrawalService.withdraw(
                    accountId,
                    new WithdrawalRequest(
                            new BigDecimal("10.00"),
                            "AUD",
                            trackReference("concurrent-withdrawal-" + suffix + "-" + index),
                            "Concurrent withdrawal"));
            return null;
        });

        Account account = accountRepository.findById(accountId).orElseThrow();

        assertBigDecimalEquals(new BigDecimal("0.00"), account.getBalance());
        assertEquals(THREADS + 1, referenceIds.size());
        assertEquals(THREADS + 1, entryRepository
                .findByAccountId(accountId, PageRequest.of(0, 50))
                .getTotalElements());
    }

    @Test
    void concurrentTransfersSerializeSourceAndTargetBalanceUpdates() throws Exception {
        Long sourceAccountId = createAccount("Concurrent Transfer Source");
        Long targetAccountId = createAccount("Concurrent Transfer Target");
        String suffix = randomSuffix();
        deposit(sourceAccountId, "concurrent-transfer-deposit-" + suffix, "100.00");

        runConcurrently(THREADS, index -> {
            transferService.transfer(new TransferRequest(
                    sourceAccountId,
                    targetAccountId,
                    new BigDecimal("10.00"),
                    "AUD",
                    trackReference("concurrent-transfer-" + suffix + "-" + index),
                    "Concurrent transfer"));
            return null;
        });

        Account sourceAccount = accountRepository.findById(sourceAccountId).orElseThrow();
        Account targetAccount = accountRepository.findById(targetAccountId).orElseThrow();

        assertBigDecimalEquals(new BigDecimal("0.00"), sourceAccount.getBalance());
        assertBigDecimalEquals(new BigDecimal("100.00"), targetAccount.getBalance());
        assertEquals(THREADS + 1, referenceIds.size());
        assertEquals(THREADS + 1, entryRepository
                .findByAccountId(sourceAccountId, PageRequest.of(0, 50))
                .getTotalElements());
        assertEquals(THREADS, entryRepository
                .findByAccountId(targetAccountId, PageRequest.of(0, 50))
                .getTotalElements());
    }

    private Long createAccount(String ownerName) {
        AccountResponse response = accountService.create(
                new CreateAccountRequest(ownerName, "AUD"));
        createdAccountIds.add(response.id());
        return response.id();
    }

    private void deposit(
            Long accountId,
            String referenceId,
            String amount) {
        depositService.deposit(
                accountId,
                new DepositRequest(
                        new BigDecimal(amount),
                        "AUD",
                        trackReference(referenceId),
                        "Concurrency setup deposit"));
    }

    private String trackReference(String referenceId) {
        referenceIds.add(referenceId);
        return referenceId;
    }

    private static void runConcurrently(
            int threadCount,
            ThrowingIndexedCallable task) throws Exception {
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Void>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                int index = i;
                Callable<Void> callable = () -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return task.call(index);
                };
                futures.add(executorService.submit(callable));
            }

            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    private static void assertBigDecimalEquals(
            BigDecimal expected,
            BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual));
    }

    private static String randomSuffix() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }

    @FunctionalInterface
    private interface ThrowingIndexedCallable {
        Void call(int index) throws Exception;
    }
}
