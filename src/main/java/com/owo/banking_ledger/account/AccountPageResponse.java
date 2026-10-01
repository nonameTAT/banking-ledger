package com.owo.banking_ledger.account;

import java.util.List;

import org.springframework.data.domain.Page;

public record AccountPageResponse(
        List<AccountResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static AccountPageResponse from(Page<AccountResponse> page) {
        return new AccountPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
