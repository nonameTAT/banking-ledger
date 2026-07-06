package com.owo.banking_ledger.account;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {
}
