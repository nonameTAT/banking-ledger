package com.owo.banking_ledger.deposit;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountKind;
import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.account.SystemAccounts;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogService;
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.common.RequestFingerprint;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.IdempotencyService;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionType;

@Service
public class DepositService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    public DepositService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
    }

    @Transactional
    public DepositResponse deposit(
            Long accountId,
            DepositRequest request) {
        String requestHash = fingerprint(accountId, request);

        Optional<LedgerTransaction> replayed = idempotencyService.claim(
                request.referenceId(),
                requestHash);

        if (replayed.isPresent()) {
            return replayResponse(replayed.get(), accountId);
        }

        String systemAccountNumber = SystemAccounts.cashAccountNumber(
                request.currency());

        Account systemAccount = accountRepository
                .findByAccountNumberForUpdate(systemAccountNumber)
                .orElseThrow(() -> BusinessException.invalidRequest(
                        "System cash account not found: "
                                + systemAccountNumber));

        Account customerAccount = accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        validateDeposit(customerAccount, systemAccount, request);

        LedgerTransaction transaction = createTransaction(request, requestHash);

        systemAccount.debit(request.amount());
        customerAccount.credit(request.amount());

        LedgerEntry systemEntry = new LedgerEntry(
                transaction,
                systemAccount,
                EntryType.DEBIT,
                request.amount(),
                systemAccount.getBalance());

        LedgerEntry customerEntry = new LedgerEntry(
                transaction,
                customerAccount,
                EntryType.CREDIT,
                request.amount(),
                customerAccount.getBalance());

        entryRepository.saveAll(
                List.of(systemEntry, customerEntry));

        transaction.complete();
        auditLogService.recordTransactionEvent(
                AuditAction.DEPOSIT_COMPLETED,
                customerAccount.getId(),
                null,
                transaction,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription());

        return new DepositResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                customerAccount.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                customerAccount.getBalance());
    }

    private static String fingerprint(Long accountId, DepositRequest request) {
        return RequestFingerprint.of(
                TransactionType.DEPOSIT.name(),
                String.valueOf(accountId),
                RequestFingerprint.normalize(request.amount()),
                request.currency(),
                request.description());
    }

    private DepositResponse replayResponse(
            LedgerTransaction transaction,
            Long accountId) {
        LedgerEntry entry = entryRepository
                .findByTransactionId(transaction.getId())
                .stream()
                .filter(candidate -> candidate.getAccount()
                        .getId()
                        .equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Replayed transaction has no entry for account "
                                + accountId));

        return new DepositResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                accountId,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                entry.getBalanceAfter());
    }

    private LedgerTransaction createTransaction(
            DepositRequest request,
            String requestHash) {
        try {
            return transactionRepository.saveAndFlush(
                    new LedgerTransaction(
                            request.referenceId(),
                            TransactionType.DEPOSIT,
                            request.amount(),
                            request.currency(),
                            request.description(),
                            requestHash));
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateTransactionException(request.referenceId());
        }
    }

    private void validateDeposit(
            Account customerAccount,
            Account systemAccount,
            DepositRequest request) {
        if (customerAccount.getAccountKind() != AccountKind.CUSTOMER) {
            throw BusinessException.invalidRequest(
                    "Deposits can only be made to customer accounts");
        }

        if (!customerAccount.getCurrency().equals(request.currency())) {
            throw BusinessException.invalidRequest(
                    "Customer account currency does not match request currency");
        }

        if (!systemAccount.getCurrency().equals(request.currency())) {
            throw BusinessException.invalidRequest(
                    "System account currency does not match request currency");
        }
    }
}
