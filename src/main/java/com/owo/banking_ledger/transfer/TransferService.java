package com.owo.banking_ledger.transfer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountKind;
import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
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
import com.owo.banking_ledger.security.AccountAccessPolicy;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;
    private final AccountAccessPolicy accessPolicy;

    public TransferService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AuditLogService auditLogService,
            IdempotencyService idempotencyService,
            AccountAccessPolicy accessPolicy) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.auditLogService = auditLogService;
        this.idempotencyService = idempotencyService;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        // Money leaves the source account, so that is the account the caller
        // must be authorized for. The target may belong to anyone.
        accessPolicy.requireAccountAccess(request.sourceAccountId());

        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw BusinessException.invalidRequest(
                    "Source and target accounts must be different");
        }

        String requestHash = fingerprint(request);

        Optional<LedgerTransaction> replayed = idempotencyService.claim(
                request.referenceId(),
                requestHash);

        if (replayed.isPresent()) {
            return replayResponse(replayed.get(), request);
        }

        long firstId = Math.min(
                request.sourceAccountId(),
                request.targetAccountId());

        long secondId = Math.max(
                request.sourceAccountId(),
                request.targetAccountId());

        Account firstAccount = accountRepository.findByIdForUpdate(firstId)
                .orElseThrow(() -> new AccountNotFoundException(firstId));

        Account secondAccount = accountRepository.findByIdForUpdate(secondId)
                .orElseThrow(() -> new AccountNotFoundException(secondId));

        Account source = firstAccount.getId()
                .equals(request.sourceAccountId())
                        ? firstAccount
                        : secondAccount;

        Account target = firstAccount.getId()
                .equals(request.targetAccountId())
                        ? firstAccount
                        : secondAccount;

        validateTransfer(source, target, request);

        LedgerTransaction transaction = transactionRepository.save(
                new LedgerTransaction(
                        request.referenceId(),
                        TransactionType.TRANSFER,
                        request.amount(),
                        request.currency(),
                        request.description(),
                        requestHash));

        source.debit(request.amount());
        target.credit(request.amount());

        LedgerEntry sourceEntry = new LedgerEntry(
                transaction,
                source,
                EntryType.DEBIT,
                request.amount(),
                source.getBalance());

        LedgerEntry targetEntry = new LedgerEntry(
                transaction,
                target,
                EntryType.CREDIT,
                request.amount(),
                target.getBalance());

        entryRepository.saveAll(List.of(sourceEntry, targetEntry));

        transaction.complete();
        auditLogService.recordTransactionEvent(
                AuditAction.TRANSFER_COMPLETED,
                source.getId(),
                target.getId(),
                transaction,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription());

        return new TransferResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                source.getId(),
                target.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                source.getBalance(),
                target.getBalance());
    }

    private static String fingerprint(TransferRequest request) {
        return RequestFingerprint.of(
                TransactionType.TRANSFER.name(),
                String.valueOf(request.sourceAccountId()),
                String.valueOf(request.targetAccountId()),
                RequestFingerprint.normalize(request.amount()),
                request.currency(),
                request.description());
    }

    private TransferResponse replayResponse(
            LedgerTransaction transaction,
            TransferRequest request) {
        List<LedgerEntry> entries = entryRepository.findByTransactionId(
                transaction.getId());

        return new TransferResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                request.sourceAccountId(),
                request.targetAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                balanceAfter(entries, request.sourceAccountId()),
                balanceAfter(entries, request.targetAccountId()));
    }

    private static BigDecimal balanceAfter(
            List<LedgerEntry> entries,
            Long accountId) {
        return entries.stream()
                .filter(entry -> entry.getAccount().getId().equals(accountId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Replayed transaction has no entry for account "
                                + accountId))
                .getBalanceAfter();
    }

    private void validateTransfer(
            Account source,
            Account target,
            TransferRequest request) {
        if (source.getAccountKind() != AccountKind.CUSTOMER
                || target.getAccountKind() != AccountKind.CUSTOMER) {
            throw BusinessException.invalidRequest(
                    "Transfers require two customer accounts");
        }

        if (!source.getCurrency().equals(request.currency())
                || !target.getCurrency().equals(request.currency())) {
            throw BusinessException.invalidRequest(
                    "Both account currencies must match the request currency");
        }
    }
}
