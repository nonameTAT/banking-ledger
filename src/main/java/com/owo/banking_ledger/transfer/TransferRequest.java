package com.owo.banking_ledger.transfer;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record TransferRequest(
        @NotNull Long sourceAccountId,

        @NotNull Long targetAccountId,

        @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,

        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,

        @NotBlank @Size(max = 64) String referenceId,

        @Size(max = 255) String description) {
}
