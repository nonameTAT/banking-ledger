package com.owo.banking_ledger.audit;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByAccountIdOrRelatedAccountIdOrderByCreatedAtDesc(
            Long accountId,
            Long relatedAccountId,
            Pageable pageable);

    List<AuditLog> findByAccountIdInOrRelatedAccountIdIn(
            Collection<Long> accountIds,
            Collection<Long> relatedAccountIds);
}
