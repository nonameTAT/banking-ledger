package com.owo.banking_ledger.reversal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReversalRequest(

        @NotBlank @Size(max = 64) String referenceId,

        @Size(max = 255) String description) {
}
