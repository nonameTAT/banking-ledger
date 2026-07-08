package com.owo.banking_ledger.audit;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final AccountRepository accountRepository;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            AccountRepository accountRepository) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
    }

    public void recordAccountEvent(
            AuditAction action,
            Long accountId,
            String details) {
        auditLogRepository.save(
                new AuditLog(
                        action,
                        accountId,
                        null,
                        null,
                        null,
                        null,
                        null,
                        details));
    }

    public void recordTransactionEvent(
            AuditAction action,
            Long accountId,
            Long relatedAccountId,
            LedgerTransaction transaction,
            BigDecimal amount,
            String currency,
            String details) {
        auditLogRepository.save(
                new AuditLog(
                        action,
                        accountId,
                        relatedAccountId,
                        transaction.getId(),
                        transaction.getReferenceId(),
                        amount,
                        currency,
                        details));
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> findAccountAuditLogs(
            Long accountId,
            Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return auditLogRepository
                .findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
                        accountId,
                        accountId,
                        pageable)
                .map(AuditLogResponse::from);
    }
}
