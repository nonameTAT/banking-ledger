package com.owo.banking_ledger.reconciliation;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface ReconciliationDifferenceRepository
        extends Repository<ReconciliationDifference, Long> {

    <S extends ReconciliationDifference> List<S> saveAll(Iterable<S> differences);

    Page<ReconciliationDifference> findAllByOrderByDetectedAtDesc(Pageable pageable);

    Page<ReconciliationDifference> findByAccountIdOrderByDetectedAtDesc(
            Long accountId,
            Pageable pageable);
}
