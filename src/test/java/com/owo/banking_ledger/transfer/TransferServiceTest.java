package com.owo.banking_ledger.transfer;

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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private LedgerEntryRepository entryRepository;

    @InjectMocks
    private TransferService transferService;

    @Test
    void transferCreatesCompletedTransactionAndLedgerEntries() {
        Account source = customerAccount(2L, "Alice", "AUD");
        source.credit(new BigDecimal("100.0000"));
        Account target = customerAccount(4L, "Bob", "AUD");
        target.credit(new BigDecimal("20.0000"));
        TransferRequest request = new TransferRequest(
                2L,
                4L,
                new BigDecimal("35.0000"),
                "AUD",
                "transfer-001",
                "Rent");

        when(transactionRepository.existsByReferenceId("transfer-001"))
                .thenReturn(false);
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(source));
        when(accountRepository.findByIdForUpdate(4L))
                .thenReturn(Optional.of(target));
        when(transactionRepository.save(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> {
                    LedgerTransaction transaction = invocation.getArgument(0);
                    ReflectionTestUtils.setField(transaction, "id", 30L);
                    return transaction;
                });

        TransferResponse response = transferService.transfer(request);

        ArgumentCaptor<LedgerTransaction> transactionCaptor =
                ArgumentCaptor.forClass(LedgerTransaction.class);
        verify(transactionRepository).save(transactionCaptor.capture());
        LedgerTransaction transaction = transactionCaptor.getValue();

        assertEquals(30L, response.transactionId());
        assertEquals("transfer-001", response.referenceId());
        assertEquals(2L, response.sourceAccountId());
        assertEquals(4L, response.targetAccountId());
        assertEquals(new BigDecimal("35.0000"), response.amount());
        assertEquals("AUD", response.currency());
        assertEquals(TransactionStatus.COMPLETED, response.status());
        assertEquals(new BigDecimal("65.0000"), response.sourceBalanceAfter());
        assertEquals(new BigDecimal("55.0000"), response.targetBalanceAfter());
        assertEquals(TransactionType.TRANSFER, transaction.getTransactionType());
        assertEquals(TransactionStatus.COMPLETED, transaction.getStatus());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LedgerEntry>> entriesCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(entryRepository).saveAll(entriesCaptor.capture());
        List<LedgerEntry> entries = entriesCaptor.getValue();

        assertEquals(2, entries.size());
        assertEntry(entries.get(0), 2L, EntryType.DEBIT, new BigDecimal("35.0000"),
                new BigDecimal("65.0000"));
        assertEntry(entries.get(1), 4L, EntryType.CREDIT, new BigDecimal("35.0000"),
                new BigDecimal("55.0000"));
    }

    @Test
    void transferLocksAccountsByAscendingId() {
        Account source = customerAccount(5L, "Alice", "AUD");
        source.credit(new BigDecimal("100.0000"));
        Account target = customerAccount(2L, "Bob", "AUD");
        TransferRequest request = new TransferRequest(
                5L,
                2L,
                new BigDecimal("10.0000"),
                "AUD",
                "transfer-002",
                null);

        when(transactionRepository.existsByReferenceId("transfer-002"))
                .thenReturn(false);
        when(accountRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(target));
        when(accountRepository.findByIdForUpdate(5L))
                .thenReturn(Optional.of(source));
        when(transactionRepository.save(any(LedgerTransaction.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        transferService.transfer(request);

        InOrder inOrder = Mockito.inOrder(accountRepository);
        inOrder.verify(accountRepository).findByIdForUpdate(2L);
        inOrder.verify(accountRepository).findByIdForUpdate(5L);
    }

    @Test
    void transferRejectsDuplicateReferenceId() {
        TransferRequest request = new TransferRequest(
                2L,
                4L,
                new BigDecimal("35.0000"),
                "AUD",
                "transfer-001",
                null);

        when(transactionRepository.existsByReferenceId("transfer-001"))
                .thenReturn(true);

        DuplicateTransactionException exception = assertThrows(
                DuplicateTransactionException.class,
                () -> transferService.transfer(request));

        assertEquals("Transaction reference already exists: transfer-001",
                exception.getMessage());
        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(entryRepository, never()).saveAll(any());
    }

    @Test
    void transferRejectsSameSourceAndTargetAccount() {
        TransferRequest request = new TransferRequest(
                2L,
                2L,
                new BigDecimal("35.0000"),
                "AUD",
                "transfer-001",
                null);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> transferService.transfer(request));

        assertEquals("Source and target accounts must be different",
                exception.getMessage());
        verify(transactionRepository, never()).existsByReferenceId(any());
    }

    private static Account customerAccount(Long id, String ownerName, String currency) {
        Account account = new Account("CUSTOMER-" + id, ownerName, currency);
        ReflectionTestUtils.setField(account, "id", id);
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
