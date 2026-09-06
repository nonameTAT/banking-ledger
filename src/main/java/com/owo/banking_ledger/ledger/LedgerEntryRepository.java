package com.owo.banking_ledger.ledger;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

/**
 * Deliberately narrower than {@code JpaRepository}: posted entries are
 * append-only, so this repository exposes inserts and reads only. There is no
 * update or delete path.
 */
public interface LedgerEntryRepository extends Repository<LedgerEntry, Long> {

    <S extends LedgerEntry> List<S> saveAll(Iterable<S> entries);

    <S extends LedgerEntry> S saveAndFlush(S entry);

    Optional<LedgerEntry> findById(Long id);

    @EntityGraph(attributePaths = "transaction")
    Page<LedgerEntry> findByAccountId(Long accountId, Pageable pageable);

    List<LedgerEntry> findByTransactionId(Long transactionId);
}
