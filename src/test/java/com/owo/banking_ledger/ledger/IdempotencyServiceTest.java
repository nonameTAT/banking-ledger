package com.owo.banking_ledger.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.RequestFingerprint;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock
    private LedgerTransactionRepository transactionRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query lockQuery;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyService(transactionRepository);
        ReflectionTestUtils.setField(
                idempotencyService,
                "entityManager",
                entityManager);

        when(entityManager.createNativeQuery(anyString())).thenReturn(lockQuery);
        when(lockQuery.setParameter(eq("key"), any())).thenReturn(lockQuery);
    }

    @Test
    void claimReturnsEmptyForANewReferenceId() {
        when(transactionRepository.findByReferenceId("deposit-001"))
                .thenReturn(Optional.empty());

        assertTrue(idempotencyService.claim("deposit-001", "hash").isEmpty());
    }

    @Test
    void claimLocksTheReferenceIdBeforeReadingIt() {
        when(transactionRepository.findByReferenceId("deposit-001"))
                .thenReturn(Optional.empty());

        idempotencyService.claim("deposit-001", "hash");

        verify(entityManager).createNativeQuery(
                "SELECT pg_advisory_xact_lock(:key)");
        verify(lockQuery).setParameter(
                "key",
                RequestFingerprint.lockKey("deposit-001"));
        verify(lockQuery).getSingleResult();
    }

    @Test
    void claimReturnsTheOriginalTransactionForAMatchingPayload() {
        LedgerTransaction original = transaction("hash");

        when(transactionRepository.findByReferenceId("deposit-001"))
                .thenReturn(Optional.of(original));

        assertSame(
                original,
                idempotencyService.claim("deposit-001", "hash").orElseThrow());
    }

    @Test
    void claimRejectsAReusedReferenceIdCarryingADifferentPayload() {
        when(transactionRepository.findByReferenceId("deposit-001"))
                .thenReturn(Optional.of(transaction("original-hash")));

        IdempotencyConflictException exception = assertThrows(
                IdempotencyConflictException.class,
                () -> idempotencyService.claim("deposit-001", "other-hash"));

        assertEquals(
                BusinessErrorCode.IDEMPOTENCY_PAYLOAD_MISMATCH,
                exception.getCode());
        assertEquals(
                "Transaction reference was already used with a different "
                        + "request payload: deposit-001",
                exception.getMessage());
    }

    @Test
    void claimRejectsAReferenceIdWhosePayloadCannotBeVerified() {
        when(transactionRepository.findByReferenceId("deposit-001"))
                .thenReturn(Optional.of(transaction(null)));

        DuplicateTransactionException exception = assertThrows(
                DuplicateTransactionException.class,
                () -> idempotencyService.claim("deposit-001", "hash"));

        assertEquals(
                BusinessErrorCode.DUPLICATE_TRANSACTION,
                exception.getCode());
    }

    private static LedgerTransaction transaction(String requestHash) {
        return new LedgerTransaction(
                "deposit-001",
                TransactionType.DEPOSIT,
                new BigDecimal("100.0000"),
                "AUD",
                "Initial deposit",
                requestHash);
    }
}
