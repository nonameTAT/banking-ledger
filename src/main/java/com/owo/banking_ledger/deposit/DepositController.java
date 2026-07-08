package com.owo.banking_ledger.deposit;

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

@Tag(name = "Deposits", description = "Deposit money into customer accounts")
@RestController
@RequestMapping("/api/accounts/{accountId}/deposits")
public class DepositController {

    private final DepositService depositService;

    public DepositController(DepositService depositService) {
        this.depositService = depositService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Deposit money into an account")
    public DepositResponse deposit(
            @PathVariable Long accountId,
            @Valid @RequestBody DepositRequest request) {
        return depositService.deposit(accountId, request);
    }
}
