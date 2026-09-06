package com.owo.banking_ledger.withdrawal;

import java.util.List;

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
import com.owo.banking_ledger.deposit.DuplicateTransactionException;
import com.owo.banking_ledger.ledger.EntryType;
import com.owo.banking_ledger.ledger.LedgerEntry;
import com.owo.banking_ledger.ledger.LedgerEntryRepository;
import com.owo.banking_ledger.ledger.LedgerTransaction;
import com.owo.banking_ledger.ledger.LedgerTransactionRepository;
import com.owo.banking_ledger.ledger.TransactionType;

@Service
public class WithdrawalService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final AuditLogService auditLogService;

    public WithdrawalService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            AuditLogService auditLogService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public WithdrawalResponse withdraw(
            Long accountId,
            WithdrawalRequest request) {
        if (transactionRepository.existsByReferenceId(request.referenceId())) {
            throw new DuplicateTransactionException(request.referenceId());
        }

        String systemAccountNumber = SystemAccounts.cashAccountNumber(
                request.currency());

        // same lock order as deposit
        Account systemAccount = accountRepository
                .findByAccountNumberForUpdate(systemAccountNumber)
                .orElseThrow(() -> BusinessException.invalidRequest(
                        "System cash account not found: " + systemAccountNumber));

        Account customerAccount = accountRepository
                .findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        validateWithdrawal(customerAccount, systemAccount, request);

        LedgerTransaction transaction = transactionRepository.save(
                new LedgerTransaction(
                        request.referenceId(),
                        TransactionType.WITHDRAWAL,
                        request.amount(),
                        request.currency(),
                        request.description()));

        customerAccount.debit(request.amount());
        systemAccount.credit(request.amount());

        LedgerEntry customerEntry = new LedgerEntry(
                transaction,
                customerAccount,
                EntryType.DEBIT,
                request.amount(),
                customerAccount.getBalance());

        LedgerEntry systemEntry = new LedgerEntry(
                transaction,
                systemAccount,
                EntryType.CREDIT,
                request.amount(),
                systemAccount.getBalance());

        entryRepository.saveAll(
                List.of(customerEntry, systemEntry));

        transaction.complete();
        auditLogService.recordTransactionEvent(
                AuditAction.WITHDRAWAL_COMPLETED,
                customerAccount.getId(),
                null,
                transaction,
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDescription());

        return new WithdrawalResponse(
                transaction.getId(),
                transaction.getReferenceId(),
                customerAccount.getId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                customerAccount.getBalance());
    }

    private void validateWithdrawal(
            Account customerAccount,
            Account systemAccount,
            WithdrawalRequest request) {
        if (customerAccount.getAccountKind() != AccountKind.CUSTOMER) {
            throw BusinessException.invalidRequest(
                    "Withdrawals are only allowed from customer accounts");
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
