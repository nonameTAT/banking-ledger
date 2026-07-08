package com.owo.banking_ledger.deposit;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DepositRequest(

        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,

        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a three-letter uppercase code") String currency,

        @NotBlank @Size(max = 64) String referenceId,

        @Size(max = 255) String description) {
}
