package com.owo.banking_ledger.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.TransactionType;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void recordAccountEventSavesAuditLog() {
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_FROZEN,
                2L,
                "Account frozen");

        ArgumentCaptor<AuditLog> auditLogCaptor =
                ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditLogCaptor.capture());
        AuditLog auditLog = auditLogCaptor.getValue();

        assertEquals(AuditAction.ACCOUNT_FROZEN, auditLog.getAction());
        assertEquals(2L, auditLog.getAccountId());
        assertEquals("Account frozen", auditLog.getDetails());
        assertNotNull(auditLog.getCreatedAt());
    }

    @Test
    void recordTransactionEventSavesAuditLog() {
        LedgerTransaction transaction = new LedgerTransaction(
                "transfer-001",
                TransactionType.TRANSFER,
                new BigDecimal("35.0000"),
                "AUD",
                "Rent",
                "fingerprint");
        ReflectionTestUtils.setField(transaction, "id", 30L);

        auditLogService.recordTransactionEvent(
                AuditAction.TRANSFER_COMPLETED,
                2L,
                4L,
                transaction,
                new BigDecimal("35.0000"),
                "AUD",
                "Rent");

        ArgumentCaptor<AuditLog> auditLogCaptor =
                ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditLogCaptor.capture());
        AuditLog auditLog = auditLogCaptor.getValue();

        assertEquals(AuditAction.TRANSFER_COMPLETED, auditLog.getAction());
        assertEquals(2L, auditLog.getAccountId());
        assertEquals(4L, auditLog.getRelatedAccountId());
        assertEquals(30L, auditLog.getTransactionId());
        assertEquals("transfer-001", auditLog.getReferenceId());
        assertEquals(new BigDecimal("35.0000"), auditLog.getAmount());
        assertEquals("AUD", auditLog.getCurrency());
        assertEquals("Rent", auditLog.getDetails());
        assertNotNull(auditLog.getCreatedAt());
    }

    @Test
    void findAccountAuditLogsReturnsLogsForAccountAndRelatedAccount() {
        AuditLog auditLog = new AuditLog(
                AuditAction.TRANSFER_COMPLETED,
                2L,
                4L,
                30L,
                "transfer-001",
                new BigDecimal("35.0000"),
                "AUD",
                "Rent");
        ReflectionTestUtils.setField(auditLog, "id", 100L);
        PageRequest pageable = PageRequest.of(0, 20);

        when(accountRepository.existsById(4L)).thenReturn(true);
        when(auditLogRepository.findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                4L,
                4L,
                pageable))
                .thenReturn(new PageImpl<>(List.of(auditLog)));

        Page<AuditLogResponse> response =
                auditLogService.findAccountAuditLogs(4L, pageable);

        assertEquals(1, response.getTotalElements());
        assertEquals(100L, response.getContent().get(0).id());
        assertEquals(AuditAction.TRANSFER_COMPLETED,
                response.getContent().get(0).action());
        verify(auditLogRepository)
                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                        4L,
                        4L,
                        pageable);
    }

    @Test
    void findAccountAuditLogsThrowsWhenAccountDoesNotExist() {
        when(accountRepository.existsById(99L)).thenReturn(false);

        AccountNotFoundException exception = assertThrows(
                AccountNotFoundException.class,
                () -> auditLogService.findAccountAuditLogs(
                        99L,
                        PageRequest.of(0, 20)));

        assertEquals("Account not found: 99", exception.getMessage());
    }
}
