package com.owo.banking_ledger.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Page<LedgerEntryResponse> findAccountEntries(
            Long accountId,
            Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        return entryRepository
                .findByAccountId(accountId, pageable)
                .map(LedgerEntryResponse::from);
    }
}
