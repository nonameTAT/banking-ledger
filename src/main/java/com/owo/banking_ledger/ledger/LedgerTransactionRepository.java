package com.owo.banking_ledger.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByReferenceId(String referenceId);
}
