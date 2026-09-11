package com.owo.banking_ledger.deposit;

import com.owo.banking_ledger.security.ApiSecurityErrorWriter;
import com.owo.banking_ledger.security.SecurityConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.ledger.TransactionStatus;

@WebMvcTest(DepositController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({ SecurityConfig.class, ApiSecurityErrorWriter.class })
@WithMockUser
class DepositControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DepositService depositService;

    @Test
    void depositReturnsCreatedResponse() throws Exception {
        when(depositService.deposit(eq(2L), any(DepositRequest.class)))
                .thenReturn(new DepositResponse(
                        10L,
                        "deposit-001",
                        2L,
                        new BigDecimal("100.0000"),
                        "AUD",
                        TransactionStatus.COMPLETED,
                        new BigDecimal("100.0000")));

        mockMvc.perform(post("/api/accounts/2/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.0000,
                                  "currency": "AUD",
                                  "referenceId": "deposit-001",
                                  "description": "Initial deposit"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(10))
                .andExpect(jsonPath("$.referenceId").value("deposit-001"))
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.amount").value(100.0000))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.balanceAfter").value(100.0000));
    }

    @Test
    void depositReturnsConflictForDuplicateReferenceId() throws Exception {
        when(depositService.deposit(eq(2L), any(DepositRequest.class)))
                .thenThrow(new DuplicateTransactionException("deposit-001"));

        mockMvc.perform(post("/api/accounts/2/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.0000,
                                  "currency": "AUD",
                                  "referenceId": "deposit-001"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSACTION"))
                .andExpect(jsonPath("$.message")
                        .value("Transaction reference already exists: deposit-001"));
    }

    @Test
    void depositReturnsBadRequestForInvalidAmount() throws Exception {
        mockMvc.perform(post("/api/accounts/2/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 0,
                                  "currency": "AUD",
                                  "referenceId": "deposit-001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
