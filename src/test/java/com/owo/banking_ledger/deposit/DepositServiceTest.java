package com.owo.banking_ledger.deposit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;

@ExtendWith(MockitoExtension.class)
class DepositServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private DepositService depositService;

    @Test
    void depositCreatesCompletedTransactionAndLedgerEntries() {
        Account systemAccount = systemCashAccount();
        Account customerAccount = customerAccount(2L, "Alice", "AUD");
        DepositRequest request = new DepositRequest(
                new BigDecimal("100.0000"),
                "AUD",
                "deposit-001",
                "Initial deposit");

        when(transactionRepository.existsByReferenceId("deposit-001"))
                .thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate("SYSTEM-CASH-AUD"))
                .thenReturn(Optional.of(systemAccount));
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(customerAccount));
        when(transactionRepository.saveAndFlush(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> {
                    LedgerTransaction transaction = invocation.getArgument(0);
                    ReflectionTestUtils.setField(transaction, "id", 10L);
                    return transaction;
                });

        DepositResponse response = depositService.deposit(2L, request);

        ArgumentCaptor<LedgerTransaction> transactionCaptor =
                ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactionRepository).saveAndFlush(transactionCaptor.capture());
        LedgerTransaction transaction = transactionCaptor.getValue();

        assertEquals(10L, response.transactionId());
        assertEquals("deposit-001", response.referenceId());
        assertEquals(2L, response.accountId());
        assertEquals(new BigDecimal("100.0000"), response.amount());
        assertEquals("AUD", response.currency());
        assertEquals(TransactionStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("100.0000"), response.balanceAfter());
        assertEquals(TransactionType.DEPOSIT, transaction.getTransactionType());
        assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());
        assertEquals(new BigDecimal("100.0000"), systemAccount.getBalance());
        assertEquals(new BigDecimal("100.0000"), customerAccount.getBalance());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entriesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();

        assertEquals(2, entries.size());
        assertEntry(entries.get(0), 1L, EntryType.DEBIT, new BigDecimal("100.0000"));
        assertEntry(entries.get(1), 2L, EntryType.CREDIT, new BigDecimal("100.0000"));
        verify(auditLogService).recordTransactionEvent(
                AuditAction.DEPOSIT_COMPLETED,
                2L,
                null,
                transaction,
                new BigDecimal("100.0000"),
                "AUD",
                "Initial deposit");
    }

    @Test
    void depositRejectsDuplicateReferenceId() {
        DepositRequest request = new DepositRequest(
                new BigDecimal("100.0000"),
                "AUD",
                "deposit-001",
                null);

        when(transactionRepository.existsByReferenceId("deposit-001"))
                .thenReturn(true);

        DuplicateTransactionException exception = assertThrows(
                DuplicateTransactionException.class,
                () -> depositService.deposit(2L, request));

        assertEquals("Transaction reference already exists: deposit-001",
                exception.getMessage());
        verify(accountRepository, never()).findByAccountNumberForUpdate(any());
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void depositRejectsFrozenAccount() {
        Account systemAccount = systemCashAccount();
        Account customerAccount = customerAccount(2L, "Alice", "AUD");
        customerAccount.freeze();
        DepositRequest request = new DepositRequest(
                new BigDecimal("100.0000"),
                "AUD",
                "deposit-002",
                null);

        when(transactionRepository.existsByReferenceId("deposit-002"))
                .thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate("SYSTEM-CASH-AUD"))
                .thenReturn(Optional.of(systemAccount));
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(customerAccount));
        when(transactionRepository.saveAndFlush(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> depositService.deposit(2L, request));

        assertEquals("Account is not active", exception.getMessage());
        verify(entryRepository, never()).saveAll(any());
        verify(auditLogService, never()).recordTransactionEvent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any());
    }

    private static Account customerAccount(Long id, String ownerName, String currency) {
        Account account = new Account("CUSTOMER-" + id, ownerName, currency);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private static Account systemCashAccount() {
        Account account = new Account("SYSTEM-CASH-AUD", "Bank System", "AUD");
        ReflectionTestUtils.setField(account, "id", 1L);
        ReflectionTestUtils.setField(account, "accountKind", AccountKind.SYSTEM);
        ReflectionTestUtils.setField(account, "accountCategory", AccountCategory.ASSET);
        return account;
    }

    private static void assertEntry(
            LedgerEntry entry,
            Long accountId,
            EntryType entryType,
            BigDecimal amount) {
        assertEquals(accountId, entry.getAccount().getId());
        assertEquals(entryType, entry.getEntryType());
        assertEquals(amount, entry.getAmount());
        assertEquals(amount, entry.getBalanceAfter());
    }
}
