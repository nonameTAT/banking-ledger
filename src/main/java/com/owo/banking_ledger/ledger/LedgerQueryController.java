package com.owo.banking_ledger.ledger;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Ledger", description = "Query account ledger entries")
@RestController
@RequestMapping("/api/accounts/{accountId}/entries")
public class LedgerQueryController {

    private final LedgerQueryService ledgerQueryService;

    public LedgerQueryController(LedgerQueryService ledgerQueryService) {
        this.ledgerQueryService = ledgerQueryService;
    }

    @GetMapping
    @Operation(summary = "Get paginated account ledger entries")
    public LedgerEntryPageResponse findEntries(
            @PathVariable Long accountId,
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        return LedgerEntryPageResponse.from(
                ledgerQueryService.findAccountEntries(accountId, pageable));
    }
}
