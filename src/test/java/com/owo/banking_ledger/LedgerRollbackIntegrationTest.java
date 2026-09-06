package com.owo.banking_ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.AccountResponse;
import com.owo.banking_ledger.account.AccountService;
import com.owo.banking_ledger.account.CreateAccountRequest;
import com.owo.banking_ledger.account.SystemAccounts;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLog;
import com.owo.banking_ledger.audit.AuditLogRepository;
import com.owo.banking_ledger.deposit.DepositRequest;
import com.owo.banking_ledger.deposit.DepositService;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.transfer.TransferRequest;
import com.owo.banking_ledger.transfer.TransferService;

/**
 * Verifies that a failed ledger or audit write rolls the whole posting back,
 * leaving no partially written transaction, entry, or balance change.
 */
@SpringBootTest
class LedgerRollbackIntegrationTest {

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

    @MockitoSpyBean
    private LedgerEntryRepository entryRepository;

    @MockitoSpyBean
    private AuditLogRepository auditLogRepository;

    private final List<Long> createdAccountIds = Collections.synchronizedList(new ArrayList<>());
    private final List<String> referenceIds = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void cleanUp() {
        reset(entryRepository, auditLogRepository);

        if (!createdAccountIds.isEmpty()) {
            auditLogRepository.deleteAll(
                    auditLogRepository.findByAccountIdInOrRelatedAccountIdIn(
                            createdAccountIds,
                            createdAccountIds));
        }

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
    void depositRollsBackWhenLedgerEntryWriteFails() {
        Long accountId = createAccount("Rollback Ledger Customer");
        String suffix = randomSuffix();
        String referenceId = trackReference("rollback-ledger-deposit-" + suffix);

        BigDecimal customerBalanceBefore = balanceOf(accountId);
        BigDecimal systemBalanceBefore = balanceOf(systemCashAccountId());
        long entryCountBefore = entryCountOf(accountId);

        // The first entry is written and flushed before the failure, so the
        // rollback has a partially posted transaction to undo.
        doAnswer(invocation -> {
            List<LedgerEntry> entries = invocation.getArgument(0);
            entryRepository.saveAndFlush(entries.getFirst());
            throw new DataIntegrityViolationException("simulated ledger entry write failure");
        }).when(entryRepository).saveAll(any());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> depositService.deposit(
                        accountId,
                        depositRequest(referenceId, "100.00")));

        assertTrue(transactionRepository.findByReferenceId(referenceId).isEmpty(),
                "ledger transaction must not survive the failed write");
        assertEquals(entryCountBefore, entryCountOf(accountId),
                "no ledger entry may survive the failed write");
        assertBigDecimalEquals(customerBalanceBefore, balanceOf(accountId));
        assertBigDecimalEquals(systemBalanceBefore, balanceOf(systemCashAccountId()));
        assertNoAuditLogFor(accountId, referenceId);

        // The rolled back reference id is free, so the retry is a clean posting.
        reset(entryRepository);
        depositService.deposit(accountId, depositRequest(referenceId, "100.00"));

        LedgerTransaction transaction = transactionRepository
                .findByReferenceId(referenceId)
                .orElseThrow();

        assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());
        assertEquals(2, entryRepository.findByTransactionId(transaction.getId()).size());
        assertBigDecimalEquals(
                customerBalanceBefore.add(new BigDecimal("100.00")),
                balanceOf(accountId));
        assertBigDecimalEquals(
                systemBalanceBefore.add(new BigDecimal("100.00")),
                balanceOf(systemCashAccountId()));
    }

    @Test
    void transferRollsBackWhenAuditLogWriteFails() {
        Long sourceAccountId = createAccount("Rollback Audit Source");
        Long targetAccountId = createAccount("Rollback Audit Target");
        String suffix = randomSuffix();

        depositService.deposit(
                sourceAccountId,
                depositRequest(
                        trackReference("rollback-audit-deposit-" + suffix),
                        "100.00"));

        String referenceId = trackReference("rollback-audit-transfer-" + suffix);
        BigDecimal sourceBalanceBefore = balanceOf(sourceAccountId);
        BigDecimal targetBalanceBefore = balanceOf(targetAccountId);
        long sourceEntryCountBefore = entryCountOf(sourceAccountId);
        long targetEntryCountBefore = entryCountOf(targetAccountId);

        // Both ledger entries are written before the audit log, so the audit
        // failure has to unwind a fully posted transaction.
        doThrow(new DataIntegrityViolationException("simulated audit log write failure"))
                .when(auditLogRepository)
                .save(argThat((AuditLog log) -> log != null
                        && log.getAction() == AuditAction.TRANSFER_COMPLETED));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> transferService.transfer(new TransferRequest(
                        sourceAccountId,
                        targetAccountId,
                        new BigDecimal("20.00"),
                        "AUD",
                        referenceId,
                        "Rollback transfer")));

        assertTrue(transactionRepository.findByReferenceId(referenceId).isEmpty(),
                "ledger transaction must not survive the failed audit write");
        assertEquals(sourceEntryCountBefore, entryCountOf(sourceAccountId),
                "no source ledger entry may survive the failed audit write");
        assertEquals(targetEntryCountBefore, entryCountOf(targetAccountId),
                "no target ledger entry may survive the failed audit write");
        assertBigDecimalEquals(sourceBalanceBefore, balanceOf(sourceAccountId));
        assertBigDecimalEquals(targetBalanceBefore, balanceOf(targetAccountId));
        assertNoAuditLogFor(sourceAccountId, referenceId);
        assertNoAuditLogFor(targetAccountId, referenceId);

        // Replaying the rolled back transfer proves the assertions above can
        // see a posting when one exists, and that the reference id is free.
        reset(auditLogRepository);
        transferService.transfer(new TransferRequest(
                sourceAccountId,
                targetAccountId,
                new BigDecimal("20.00"),
                "AUD",
                referenceId,
                "Rollback transfer"));

        LedgerTransaction transaction = transactionRepository
                .findByReferenceId(referenceId)
                .orElseThrow();

        assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());
        assertEquals(2, entryRepository.findByTransactionId(transaction.getId()).size());
        assertBigDecimalEquals(
                sourceBalanceBefore.subtract(new BigDecimal("20.00")),
                balanceOf(sourceAccountId));
        assertBigDecimalEquals(
                targetBalanceBefore.add(new BigDecimal("20.00")),
                balanceOf(targetAccountId));
    }

    private void assertNoAuditLogFor(Long accountId, String referenceId) {
        boolean recorded = auditLogRepository
                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                        accountId,
                        accountId,
                        PageRequest.of(0, 50))
                .getContent()
                .stream()
                .anyMatch(log -> referenceId.equals(log.getReferenceId()));

        assertFalse(recorded, "no audit log may survive the failed transaction");
    }

    private DepositRequest depositRequest(String referenceId, String amount) {
        return new DepositRequest(
                new BigDecimal(amount),
                "AUD",
                referenceId,
                "Rollback deposit");
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

    private Long systemCashAccountId() {
        return accountRepository
                .findByAccountNumber(SystemAccounts.cashAccountNumber("AUD"))
                .orElseThrow()
                .getId();
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
