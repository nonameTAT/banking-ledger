package com.owo.banking_ledger.reconciliation;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

public interface ReconciliationRunRepository
        extends Repository<ReconciliationRun, Long> {

    ReconciliationRun saveAndFlush(ReconciliationRun run);

    Page<ReconciliationRun> findAllByOrderByStartedAtDesc(Pageable pageable);

    Optional<ReconciliationRun> findFirstByOrderByStartedAtDesc();
}
