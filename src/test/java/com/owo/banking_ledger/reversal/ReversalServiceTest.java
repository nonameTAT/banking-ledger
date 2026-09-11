package com.owo.banking_ledger.reversal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountCategory;
import com.owo.banking_ledger.account.AccountKind;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogService;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.IdempotencyService;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.ReversalNotAllowedException;
import com.owo.banking_ledger.ledger.TransactionNotFoundException;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;
import com.owo.banking_ledger.security.AccountAccessPolicy;

@ExtendWith(MockitoExtension.class)
class ReversalServiceTest {

    private static final String OWNER_SUBJECT = "owner-subject";

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private AccountAccessPolicy accessPolicy;

    @InjectMocks
    private ReversalService reversalService;

    @Test
    void reverseMirrorsEveryEntryOfTheOriginalTransaction() {
        Account systemAccount = systemCashAccount(new BigDecimal("100.0000"));
        Account customerAccount = customerAccount(2L, new BigDecimal("100.0000"));
        LedgerTransaction original = completedDeposit();

        when(idempotencyService.claim(eq("reversal-001"), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(original));
        when(entryRepository.findByTransactionId(10L))
                .thenReturn(List.of(
                        new LedgerEntry(
                                original,
                                systemAccount,
                                EntryType.DEBIT,
                                new BigDecimal("100.0000"),
                                new BigDecimal("100.0000")),
                        new LedgerEntry(
                                original,
                                customerAccount,
                                EntryType.CREDIT,
                                new BigDecimal("100.0000"),
                                new BigDecimal("100.0000"))));
        when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(systemAccount));
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(customerAccount));
        when(transactionRepository.saveAndFlush(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> {
                    LedgerTransaction reversal = invocation.getArgument(0);
                    ReflectionTestUtils.setField(reversal, "id", 20L);
                    return reversal;
                });

        ReversalResponse response = reversalService.reverse(
                10L,
                new ReversalRequest("reversal-001", null));

        assertEquals(20L, response.transactionId());
        assertEquals("reversal-001", response.referenceId());
        assertEquals(10L, response.originalTransactionId());
        assertEquals("deposit-001", response.originalReferenceId());
        assertEquals(TransactionStatus.COMPLETED, response.status());

        // The original keeps its entries and is marked reversed.
        assertEquals(TransactionStatus.REVERSED, original.getStatus());

        ArgumentCaptor<List<LedgerEntry>> entriesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> mirrored = entriesCaptor.getValue();

        assertEquals(2, mirrored.size());
        assertEquals(EntryType.CREDIT, mirrored.get(0).getEntryType());
        assertEquals(EntryType.DEBIT, mirrored.get(1).getEntryType());
        assertSame(original, mirrored.get(0).getTransaction().getReversalOf());

        // Both accounts are back where they started.
        assertBigDecimalEquals(BigDecimal.ZERO, systemAccount.getBalance());
        assertBigDecimalEquals(BigDecimal.ZERO, customerAccount.getBalance());

        verify(auditLogService).recordTransactionEvent(
                eq(AuditAction.TRANSACTION_REVERSED),
                eq(1L),
                eq(2L),
                any(),
                any(),
                eq("AUD"),
                eq("Reversal of deposit-001"));
    }

    @Test
    void reverseReplaysAnExistingReversal() {
        LedgerTransaction original = completedDeposit();
        LedgerTransaction existing = LedgerTransaction.reversing(
                original,
                "reversal-001",
                "Reversal of deposit-001",
                "fingerprint");
        ReflectionTestUtils.setField(existing, "id", 20L);
        existing.complete();

        when(idempotencyService.claim(eq("reversal-001"), any()))
                .thenReturn(Optional.of(existing));

        ReversalResponse response = reversalService.reverse(
                10L,
                new ReversalRequest("reversal-001", null));

        assertEquals(20L, response.transactionId());
        assertEquals(10L, response.originalTransactionId());
        verify(transactionRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void reverseRejectsATransactionThatIsAlreadyReversed() {
        LedgerTransaction original = completedDeposit();
        original.markReversed();

        when(idempotencyService.claim(eq("reversal-001"), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(original));

        ReversalNotAllowedException exception = assertThrows(
                ReversalNotAllowedException.class,
                () -> reversalService.reverse(
                        10L,
                        new ReversalRequest("reversal-001", null)));

        assertEquals(
                "Transaction has already been reversed: deposit-001",
                exception.getMessage());
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void reverseRejectsAReversal() {
        LedgerTransaction original = completedDeposit();
        LedgerTransaction reversal = LedgerTransaction.reversing(
                original,
                "reversal-001",
                "Reversal of deposit-001",
                "fingerprint");
        ReflectionTestUtils.setField(reversal, "id", 20L);
        reversal.complete();

        when(idempotencyService.claim(eq("reversal-002"), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(20L))
                .thenReturn(Optional.of(reversal));

        ReversalNotAllowedException exception = assertThrows(
                ReversalNotAllowedException.class,
                () -> reversalService.reverse(
                        20L,
                        new ReversalRequest("reversal-002", null)));

        assertEquals(
                "A reversal cannot itself be reversed: reversal-001",
                exception.getMessage());
    }

    @Test
    void reverseRejectsATransactionThatNeverCompleted() {
        LedgerTransaction pending = new LedgerTransaction(
                "deposit-001",
                TransactionType.DEPOSIT,
                new BigDecimal("100.0000"),
                "AUD",
                "Initial deposit",
                "fingerprint");
        ReflectionTestUtils.setField(pending, "id", 10L);

        when(idempotencyService.claim(eq("reversal-001"), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(10L))
                .thenReturn(Optional.of(pending));

        ReversalNotAllowedException exception = assertThrows(
                ReversalNotAllowedException.class,
                () -> reversalService.reverse(
                        10L,
                        new ReversalRequest("reversal-001", null)));

        assertEquals(
                "Only completed transactions can be reversed: deposit-001",
                exception.getMessage());
    }

    @Test
    void reverseThrowsWhenTheTransactionDoesNotExist() {
        when(idempotencyService.claim(eq("reversal-001"), any()))
                .thenReturn(Optional.empty());
        when(transactionRepository.findByIdForUpdate(99L))
                .thenReturn(Optional.empty());

        TransactionNotFoundException exception = assertThrows(
                TransactionNotFoundException.class,
                () -> reversalService.reverse(
                        99L,
                        new ReversalRequest("reversal-001", null)));

        assertEquals("Transaction not found: 99", exception.getMessage());
    }

    private static LedgerTransaction completedDeposit() {
        LedgerTransaction transaction = new LedgerTransaction(
                "deposit-001",
                TransactionType.DEPOSIT,
                new BigDecimal("100.0000"),
                "AUD",
                "Initial deposit",
                "fingerprint");
        ReflectionTestUtils.setField(transaction, "id", 10L);
        transaction.complete();
        return transaction;
    }

    private static Account customerAccount(Long id, BigDecimal balance) {
        Account account = new Account("CUSTOMER-" + id, "Alice", "AUD", OWNER_SUBJECT);
        ReflectionTestUtils.setField(account, "id", id);
        ReflectionTestUtils.setField(account, "balance", balance);
        return account;
    }

    private static Account systemCashAccount(BigDecimal balance) {
        Account account = new Account("SYSTEM-CASH-AUD", "Bank System", "AUD", null);
        ReflectionTestUtils.setField(account, "id", 1L);
        ReflectionTestUtils.setField(account, "accountKind", AccountKind.SYSTEM);
        ReflectionTestUtils.setField(
                account,
                "accountCategory",
                AccountCategory.ASSET);
        ReflectionTestUtils.setField(account, "balance", balance);
        return account;
    }

    private static void assertBigDecimalEquals(
            BigDecimal expected,
            BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
