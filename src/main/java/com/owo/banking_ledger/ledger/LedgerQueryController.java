package com.owo.banking_ledger.ledger;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts/{accountId}/entries")
public class LedgerQueryController {

    private final LedgerQueryService ledgerQueryService;

    public LedgerQueryController(LedgerQueryService ledgerQueryService) {
        this.ledgerQueryService = ledgerQueryService;
    }

    @GetMapping
    public List<LedgerEntryResponse> findEntries(
            @PathVariable Long accountId) {
        return ledgerQueryService.findAccountEntries(accountId);
    }
}
