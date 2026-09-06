package com.owo.banking_ledger.ledger;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, Long> {

    Optional<LedgerTransaction> findByReferenceId(String referenceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM LedgerTransaction t WHERE t.id = :id")
    Optional<LedgerTransaction> findByIdForUpdate(@Param("id") Long id);
}
