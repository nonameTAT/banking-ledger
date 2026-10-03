package com.owo.banking_ledger.account;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogService;
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.security.AccountAccessPolicy;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final AuditLogService auditLogService;
    private final AccountAccessPolicy accessPolicy;

    public AccountService(
            AccountRepository accountRepository,
            AuditLogService auditLogService,
            AccountAccessPolicy accessPolicy) {
        this.accountRepository = accountRepository;
        this.auditLogService = auditLogService;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        validateSupportedCurrency(request.currency());

        String accountNumber = generateAccountNumber();

        // An account belongs to whoever opened it. There is no way to open an
        // account on another identity's behalf, so ownership can never be
        // assigned to a subject the caller does not control.
        Account account = new Account(
                accountNumber,
                request.ownerName(),
                request.currency(),
                accessPolicy.currentSubject());

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

        accessPolicy.requireAccountAccess(account);

        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public Page<AccountResponse> findAccounts(
            String ownerSubject,
            AccountKind accountKind,
            Pageable pageable) {
        String permittedOwner = accessPolicy.authorizeAccountListing(ownerSubject);

        Specification<Account> filter = Specification.unrestricted();

        if (permittedOwner != null) {
            filter = filter.and(AccountRepository.ownedBy(permittedOwner));
        }

        if (accountKind != null) {
            filter = filter.and(AccountRepository.ofKind(accountKind));
        }

        // Ordered by id whatever the caller asked for. Ids are unique and never
        // change, so the order is total and every page agrees with the others.
        Pageable byId = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.ASC, "id"));

        return accountRepository
                .findAll(filter, byId)
                .map(AccountResponse::from);
    }

    @Transactional
    public AccountResponse freeze(Long id) {
        // Freezing blocks the account holder's own access, so it is a bank
        // action rather than something a customer may do to their account.
        accessPolicy.requireAdmin("freeze an account");

        Account account = accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        validateCustomerAccount(account, "frozen");

        account.freeze();
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_FROZEN,
                account.getId(),
                "Account frozen");

        return AccountResponse.from(account);
    }

    @Transactional
    public AccountResponse unfreeze(Long id) {
        // Freezing blocks the account holder's own access, so it is a bank
        // action rather than something a customer may do to their account.
        accessPolicy.requireAdmin("unfreeze an account");

        Account account = accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccountNotFoundException(id));

        validateCustomerAccount(account, "unfrozen");

        account.unfreeze();
        auditLogService.recordAccountEvent(
                AuditAction.ACCOUNT_UNFROZEN,
                account.getId(),
                "Account unfrozen");

        return AccountResponse.from(account);
    }

    private void validateSupportedCurrency(String currency) {
        boolean supported = accountRepository.existsByAccountNumberAndAccountKind(
                SystemAccounts.cashAccountNumber(currency),
                AccountKind.SYSTEM);

        if (!supported) {
            throw BusinessException.invalidRequest(
                    "Currency is not supported: " + currency);
        }
    }

    private void validateCustomerAccount(Account account, String operation) {
        if (account.getAccountKind() != AccountKind.CUSTOMER) {
            throw BusinessException.invalidRequest(
                    "Only customer accounts can be " + operation);
        }
    }

    private String generateAccountNumber() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 16)
                .toUpperCase();
    }
}
