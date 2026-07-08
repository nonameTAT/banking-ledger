package com.owo.banking_ledger.ledger;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Long> {

    @EntityGraph(attributePaths = "transaction")
    Page<LedgerEntry> findByAccountId(Long accountId, Pageable pageable);

    List<LedgerEntry> findByTransactionId(Long transactionId);
}
