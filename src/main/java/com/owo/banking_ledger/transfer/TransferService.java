package com.owo.banking_ledger.transfer;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountKind;
import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionType;

@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public TransferService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional
    public TransferResponse transfer(TransferRequest request) {
        if (request.sourceAccountId().equals(request.targetAccountId())) {
            throw BusinessException.invalidRequest(
                    "Source and target accounts must be different");
        }

        if (transactionRepository.existsByReferenceId(request.referenceId())) {
            throw new DuplicateTransactionException(request.referenceId());
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
                        request.description()));

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
