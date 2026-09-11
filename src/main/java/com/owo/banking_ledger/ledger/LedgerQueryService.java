package com.owo.banking_ledger.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.security.AccountAccessPolicy;

@Service
public class LedgerQueryService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final AccountAccessPolicy accessPolicy;

    public LedgerQueryService(
            AccountRepository accountRepository,
            LedgerEntryRepository entryRepository,
            AccountAccessPolicy accessPolicy) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> findAccountEntries(
            Long accountId,
            Pageable pageable) {
        if (!accountRepository.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }

        accessPolicy.requireAccountAccess(accountId);

        return entryRepository
                .findByAccountId(accountId, pageable)
                .map(LedgerEntryResponse::from);
    }
}
