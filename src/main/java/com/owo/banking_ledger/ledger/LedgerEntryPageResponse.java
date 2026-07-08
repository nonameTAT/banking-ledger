package com.owo.banking_ledger.ledger;

import java.util.List;

import org.springframework.data.domain.Page;

public record LedgerEntryPageResponse(
        List<LedgerEntryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static LedgerEntryPageResponse from(Page<LedgerEntryResponse> page) {
        return new LedgerEntryPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
