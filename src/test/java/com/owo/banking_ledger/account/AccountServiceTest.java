package com.owo.banking_ledger.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AccountService accountService;

    @Test
    void createSavesNewCustomerAccount() {
        CreateAccountRequest request = new CreateAccountRequest("Alice", "AUD");

        when(accountRepository.save(any(Account.class)))
                .thenAnswer(invocation -> {
                    Account account = invocation.getArgument(0);
                    ReflectionTestUtils.setField(account, "id", 1L);
                    return account;
                });

        AccountResponse response = accountService.create(request);

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();

        assertEquals(1L, response.id());
        assertEquals(savedAccount.getAccountNumber(), response.accountNumber());
        assertEquals("Alice", savedAccount.getOwnerName());
        assertEquals("AUD", savedAccount.getCurrency());
        assertEquals(AccountStatus.ACTIVE, savedAccount.getStatus());
        assertEquals(AccountKind.CUSTOMER, savedAccount.getAccountKind());
        assertEquals(AccountCategory.LIABILITY, savedAccount.getAccountCategory());
        assertEquals(BigDecimal.ZERO, savedAccount.getBalance());
        assertNotNull(savedAccount.getCreatedAt());
        assertTrue(savedAccount.getAccountNumber().matches("[A-F0-9]{16}"));
    }

    @Test
    void findByIdReturnsAccount() {
        Account account = new Account("ABCDEF1234567890", "Alice", "AUD");
        ReflectionTestUtils.setField(account, "id", 1L);

        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.findById(1L);

        assertEquals(1L, response.id());
        assertEquals("ABCDEF1234567890", response.accountNumber());
        assertEquals("Alice", response.ownerName());
        assertEquals("AUD", response.currency());
        assertEquals(AccountStatus.ACTIVE, response.status());
        assertEquals(BigDecimal.ZERO, response.balance());
    }

    @Test
    void findByIdThrowsWhenAccountDoesNotExist() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        AccountNotFoundException exception = assertThrows(
                AccountNotFoundException.class,
                () -> accountService.findById(99L));

        assertEquals("Account not found: 99", exception.getMessage());
    }

    @Test
    void freezeMarksAccountFrozen() {
        Account account = new Account("ABCDEF1234567890", "Alice", "AUD");
        ReflectionTestUtils.setField(account, "id", 1L);

        when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(account));

        AccountResponse response = accountService.freeze(1L);

        assertEquals(AccountStatus.FROZEN, account.getStatus());
        assertEquals(AccountStatus.FROZEN, response.status());
    }

    @Test
    void unfreezeMarksAccountActive() {
        Account account = new Account("ABCDEF1234567890", "Alice", "AUD");
        ReflectionTestUtils.setField(account, "id", 1L);
        account.freeze();

        when(accountRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(account));

        AccountResponse response = accountService.unfreeze(1L);

        assertEquals(AccountStatus.ACTIVE, account.getStatus());
        assertEquals(AccountStatus.ACTIVE, response.status());
    }

    @Test
    void freezeThrowsWhenAccountDoesNotExist() {
        when(accountRepository.findByIdForUpdate(99L))
                .thenReturn(Optional.empty());

        AccountNotFoundException exception = assertThrows(
                AccountNotFoundException.class,
                () -> accountService.freeze(99L));

        assertEquals("Account not found: 99", exception.getMessage());
    }
}
