package com.owo.banking_ledger.account;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@Tag(name = "Accounts", description = "Create and read customer accounts")
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a customer account")
    public AccountResponse create(
            @Valid @RequestBody CreateAccountRequest request) {
        return accountService.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an account by id")
    public AccountResponse findById(@PathVariable Long id) {
        return accountService.findById(id);
    }

    @PostMapping("/{id}/freeze")
    @Operation(summary = "Freeze an account")
    public AccountResponse freeze(@PathVariable Long id) {
        return accountService.freeze(id);
    }

    @PostMapping("/{id}/unfreeze")
    @Operation(summary = "Unfreeze an account")
    public AccountResponse unfreeze(@PathVariable Long id) {
        return accountService.unfreeze(id);
    }

}
