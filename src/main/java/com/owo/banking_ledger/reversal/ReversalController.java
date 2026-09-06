package com.owo.banking_ledger.reversal;

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

@Tag(
        name = "Reversals",
        description = "Correct a posted transaction with a reversal")
@RestController
@RequestMapping("/api/transactions/{transactionId}/reversals")
public class ReversalController {

    private final ReversalService reversalService;

    public ReversalController(ReversalService reversalService) {
        this.reversalService = reversalService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reverse a completed transaction")
    public ReversalResponse reverse(
            @PathVariable Long transactionId,
            @Valid @RequestBody ReversalRequest request) {
        return reversalService.reverse(transactionId, request);
    }
}
