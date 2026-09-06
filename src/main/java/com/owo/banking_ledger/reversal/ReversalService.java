package com.owo.banking_ledger.reversal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.audit.AuditAction;
import com.owo.banking_ledger.audit.AuditLogService;
import com.owo.banking_ledger.common.RequestFingerprint;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.IdempotencyService;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.ReversalNotAllowedException;
import com.owo.banking_ledger.ledger.TransactionNotFoundException;
import com.owo.banking_ledger.ledger.TransactionStatus;
import com.owo.banking_ledger.ledger.TransactionType;

/**
 * Corrects a posted transaction by writing a new transaction that mirrors every
 * entry of the original. Nothing already posted is touched: the original keeps
 * its entries and is marked {@code REVERSED}, and the correction is linked back
 * to it.
 */
@Service
public class ReversalService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AuditLogService auditLogService;
    private final IdempotencyService idempotencyService;

    public ReversalService(
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
    public ReversalResponse reverse(
            Long transactionId,
            ReversalRequest request) {
        String requestHash = fingerprint(transactionId, request);

        Optional<LedgerTransaction> replayed = idempotencyService.claim(
                request.referenceId(),
                requestHash);

        if (replayed.isPresent()) {
            return ReversalResponse.from(replayed.get());
        }

        LedgerTransaction original = transactionRepository
                .findByIdForUpdate(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        validateReversible(original);

        List<LedgerEntry> originalEntries = entryRepository
                .findByTransactionId(original.getId());

        if (originalEntries.isEmpty()) {
            throw new ReversalNotAllowedException(
                    "Transaction has no ledger entries to reverse: "
                            + original.getReferenceId());
        }

        LedgerTransaction reversal = transactionRepository.saveAndFlush(
                LedgerTransaction.reversing(
                        original,
                        request.referenceId(),
                        description(original, request),
                        requestHash));

        Map<Long, Account> accounts = lockAccounts(originalEntries);
        List<LedgerEntry> mirroredEntries = new ArrayList<>();

        for (LedgerEntry originalEntry : originalEntries) {
            Account account = accounts.get(originalEntry.getAccount().getId());
            EntryType mirroredType = mirror(originalEntry.getEntryType());

            if (mirroredType == EntryType.DEBIT) {
                account.debit(originalEntry.getAmount());
            } else {
                account.credit(originalEntry.getAmount());
            }

            mirroredEntries.add(new LedgerEntry(
                    reversal,
                    account,
                    mirroredType,
                    originalEntry.getAmount(),
                    account.getBalance()));
        }

        entryRepository.saveAll(mirroredEntries);

        reversal.complete();
        original.markReversed();

        List<Long> accountIds = List.copyOf(accounts.keySet());
        auditLogService.recordTransactionEvent(
                AuditAction.TRANSACTION_REVERSED,
                accountIds.getFirst(),
                accountIds.size() > 1 ? accountIds.get(1) : null,
                reversal,
                reversal.getAmount(),
                reversal.getCurrency(),
                reversal.getDescription());

        return ReversalResponse.from(reversal);
    }

    /**
     * Locks every account the original touched, lowest id first, so concurrent
     * postings cannot deadlock against each other.
     */
    private Map<Long, Account> lockAccounts(List<LedgerEntry> originalEntries) {
        Map<Long, Account> accounts = new LinkedHashMap<>();

        originalEntries.stream()
                .map(entry -> entry.getAccount().getId())
                .distinct()
                .sorted()
                .forEach(accountId -> accounts.put(
                        accountId,
                        accountRepository.findByIdForUpdate(accountId)
                                .orElseThrow(() -> new AccountNotFoundException(
                                        accountId))));

        return accounts;
    }

    private static void validateReversible(LedgerTransaction original) {
        if (original.getTransactionType() == TransactionType.REVERSAL) {
            throw new ReversalNotAllowedException(
                    "A reversal cannot itself be reversed: "
                            + original.getReferenceId());
        }

        if (original.getStatus() == TransactionStatus.REVERSED) {
            throw new ReversalNotAllowedException(
                    "Transaction has already been reversed: "
                            + original.getReferenceId());
        }

        if (original.getStatus() != TransactionStatus.COMPLETED) {
            throw new ReversalNotAllowedException(
                    "Only completed transactions can be reversed: "
                            + original.getReferenceId());
        }
    }

    private static EntryType mirror(EntryType entryType) {
        return entryType == EntryType.DEBIT
                ? EntryType.CREDIT
                : EntryType.DEBIT;
    }

    private static String description(
            LedgerTransaction original,
            ReversalRequest request) {
        return request.description() == null || request.description().isBlank()
                ? "Reversal of " + original.getReferenceId()
                : request.description();
    }

    private static String fingerprint(
            Long transactionId,
            ReversalRequest request) {
        return RequestFingerprint.of(
                TransactionType.REVERSAL.name(),
                String.valueOf(transactionId),
                request.description());
    }
}
