package com.owo.banking_ledger.ledger;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;

@Service
public class LedgerQueryService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerQueryService(
            AccountRepository accountRepository,
            LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
    }

    @Transactional(readOnly = true)
    public List<LedgerEntryResponse> findAccountEntries(Long accountId) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return entryRepository
                .findByAccountIdOrderByCreatedAtDesc(accountId)
                .stream()
                .map(LedgerEntryResponse::from)
                .toList();
    }
}
