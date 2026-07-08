package com.owo.banking_ledger.withdrawal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Withdrawals", description = "Withdraw money from customer accounts")
@RestController
@RequestMapping("/api/accounts/{accountId}/withdrawals")
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    public WithdrawalController(WithdrawalService withdrawalService) {
        this.withdrawalService = withdrawalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Withdraw money from an account")
    public WithdrawalResponse withdraw(
            @PathVariable Long accountId,
            @Valid @RequestBody WithdrawalRequest request) {
        return withdrawalService.withdraw(accountId, request);
    }
}
