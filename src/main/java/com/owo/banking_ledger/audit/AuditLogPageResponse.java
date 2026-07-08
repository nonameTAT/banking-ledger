package com.owo.banking_ledger.audit;

import java.util.List;

import org.springframework.data.domain.Page;

public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AuditLogPageResponse from(Page<AuditLogResponse> page) {
        return new AuditLogPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
