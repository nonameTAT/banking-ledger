package com.owo.banking_ledger.reconciliation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.owo.banking_ledger.reconciliation.ReconciliationResponses.DifferencePageResponse;
import com.owo.banking_ledger.reconciliation.ReconciliationResponses.RunPageResponse;
import com.owo.banking_ledger.reconciliation.ReconciliationResponses.RunResponse;
import com.owo.banking_ledger.security.AccountAccessPolicy;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Makes reconciliation results queryable after the fact.
 *
 * <p>Every operation here reads across all accounts, so it carries the
 * administrative permission rather than account ownership.
 */
@Tag(
        name = "Reconciliation",
        description = "Query ledger reconciliation runs and differences")
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final AccountAccessPolicy accessPolicy;

    public ReconciliationController(
            ReconciliationService reconciliationService,
            AccountAccessPolicy accessPolicy) {
        this.reconciliationService = reconciliationService;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/runs")
    @Operation(summary = "Get paginated reconciliation runs, newest first")
    public RunPageResponse findRuns(
            @PageableDefault(
                    size = 20,
                    sort = "startedAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        accessPolicy.requireAdmin("read reconciliation runs");

        return RunPageResponse.from(reconciliationService.findRuns(pageable));
    }

    @GetMapping("/differences")
    @Operation(
            summary = "Get paginated reconciliation differences, newest first",
            description = "Optionally filtered to a single account")
    public DifferencePageResponse findDifferences(
            @RequestParam(required = false) Long accountId,
            @PageableDefault(
                    size = 20,
                    sort = "detectedAt",
                    direction = Sort.Direction.DESC) Pageable pageable) {
        accessPolicy.requireAdmin("read reconciliation differences");

        return DifferencePageResponse.from(
                reconciliationService.findDifferences(accountId, pageable));
    }

    /**
     * Runs the check immediately instead of waiting for the timer, for use when
     * something is already suspected.
     */
    @PostMapping("/runs")
    @Operation(summary = "Run reconciliation now")
    public RunResponse reconcileNow() {
        accessPolicy.requireAdmin("run reconciliation");

        return RunResponse.from(reconciliationService.reconcile());
    }
}
