package com.owo.banking_ledger.account;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogService;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;

    public AccountService(
            AccountRepository accountRepository,
            AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        String accountNumber = generateAccountNumber();

        Account account = new Account(
                accountNumber,
                request.ownerName(),
                request.currency());

        Account savedAccount = accountRepository.save(account);
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_CREATED,
                savedAccount.getId(),
                "Account created");

        return AccountResponse.from(savedAccount);
    }

    @Transactional(readOnly = true)
    public AccountResponse findById(Long id) {
        Account account = accountRepository.findById(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse freeze(Long id) {
        Account account = accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        account.freeze();
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_FROZEN,
                account.getId(),
                "Account frozen");

        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse unfreeze(Long id) {
        Account account = accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        account.unfreeze();
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_UNFROZEN,
                account.getId(),
                "Account unfrozen");

        return AccountResponse.from(account);
    }

    private String generateAccountNumber() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase();
    }
}
