package com.owo.banking_ledger.withdrawal;

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
import com.owo.banking_ledger.deposit.DuplicateTransactionException;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;

@ExtendWith(MockitoExtension.class)
class WithdrawalServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private WithdrawalService withdrawalService;

    @Test
    void withdrawCreatesCompletedTransactionAndLedgerEntries() {
        Account systemAccount = systemCashAccount(new BigDecimal("100.0000"));
        Account customerAccount = customerAccount(2L, "Alice", "AUD");
        customerAccount.credit(new BigDecimal("100.0000"));
        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("40.0000"),
                "AUD",
                "withdrawal-001",
                "ATM withdrawal");

        when(transactionRepository.existsByReferenceId("withdrawal-001"))
                .thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate("SYSTEM-CASH-AUD"))
                .thenReturn(Optional.of(systemAccount));
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(customerAccount));
        when(transactionRepository.save(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> {
                    LedgerTransaction transaction = invocation.getArgument(0);
                    ReflectionTestUtils.setField(transaction, "id", 20L);
                    return transaction;
                });

        WithdrawalResponse response = withdrawalService.withdraw(2L, request);

        ArgumentCaptor<LedgerTransaction> transactionCaptor =
                ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        LedgerTransaction transaction = transactionCaptor.getValue();

        assertEquals(20L, response.transactionId());
        assertEquals("withdrawal-001", response.referenceId());
        assertEquals(2L, response.accountId());
        assertEquals(new BigDecimal("40.0000"), response.amount());
        assertEquals("AUD", response.currency());
        assertEquals(TransactionStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("60.0000"), response.balanceAfter());
        assertEquals(TransactionType.WITHDRAWAL, transaction.getTransactionType());
        assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());
        assertEquals(new BigDecimal("60.0000"), customerAccount.getBalance());
        assertEquals(new BigDecimal("60.0000"), systemAccount.getBalance());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entriesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();

        assertEquals(2, entries.size());
        assertEntry(entries.get(0), 2L, EntryType.DEBIT, new BigDecimal("40.0000"),
                new BigDecimal("60.0000"));
        assertEntry(entries.get(1), 1L, EntryType.CREDIT, new BigDecimal("40.0000"),
                new BigDecimal("60.0000"));
        verify(auditLogService).recordTransactionEvent(
                AuditAction.WITHDRAWAL_COMPLETED,
                2L,
                null,
                transaction,
                new BigDecimal("40.0000"),
                "AUD",
                "ATM withdrawal");
    }

    @Test
    void withdrawRejectsDuplicateReferenceId() {
        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("40.0000"),
                "AUD",
                "withdrawal-001",
                null);

        when(transactionRepository.existsByReferenceId("withdrawal-001"))
                .thenReturn(true);

        DuplicateTransactionException exception = assertThrows(
                DuplicateTransactionException.class,
                () -> withdrawalService.withdraw(2L, request));

        assertEquals("Transaction reference already exists: withdrawal-001",
                exception.getMessage());
        verify(accountRepository, never()).findByAccountNumberForUpdate(any());
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void withdrawRejectsFrozenAccount() {
        Account systemAccount = systemCashAccount(new BigDecimal("100.0000"));
        Account customerAccount = customerAccount(2L, "Alice", "AUD");
        customerAccount.credit(new BigDecimal("100.0000"));
        customerAccount.freeze();
        WithdrawalRequest request = new WithdrawalRequest(
                new BigDecimal("40.0000"),
                "AUD",
                "withdrawal-002",
                null);

        when(transactionRepository.existsByReferenceId("withdrawal-002"))
                .thenReturn(false);
        when(accountRepository.findByAccountNumberForUpdate("SYSTEM-CASH-AUD"))
                .thenReturn(Optional.of(systemAccount));
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(customerAccount));
        when(transactionRepository.save(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> withdrawalService.withdraw(2L, request));

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

    private static Account systemCashAccount(BigDecimal balance) {
        Account account = new Account("SYSTEM-CASH-AUD", "Bank System", "AUD");
        ReflectionTestUtils.setField(account, "id", 1L);
        ReflectionTestUtils.setField(account, "accountKind", AccountKind.SYSTEM);
        ReflectionTestUtils.setField(account, "accountCategory", AccountCategory.ASSET);
        ReflectionTestUtils.setField(account, "balance", balance);
        return account;
    }

    private static void assertEntry(
            LedgerEntry entry,
            Long accountId,
            EntryType entryType,
            BigDecimal amount,
            BigDecimal balanceAfter) {
        assertEquals(accountId, entry.getAccount().getId());
        assertEquals(entryType, entry.getEntryType());
        assertEquals(amount, entry.getAmount());
        assertEquals(balanceAfter, entry.getBalanceAfter());
    }
}
