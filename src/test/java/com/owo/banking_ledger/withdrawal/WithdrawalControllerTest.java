package com.owo.banking_ledger.withdrawal;

import com.owo.banking_ledger.ObservabilitySliceConfiguration;
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

import com.owo.banking_ledger.deposit.DuplicateTransactionException;
import com.owo.banking_ledger.ledger.TransactionStatus;

@WebMvcTest(WithdrawalController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({
        SecurityConfig.class,
        ApiSecurityErrorWriter.class,
        ObservabilitySliceConfiguration.class })
@WithMockUser
class WithdrawalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WithdrawalService withdrawalService;

    @Test
    void withdrawReturnsCreatedResponse() throws Exception {
        when(withdrawalService.withdraw(eq(2L), any(WithdrawalRequest.class)))
                .thenReturn(new WithdrawalResponse(
                        20L,
                        "withdrawal-001",
                        2L,
                        new BigDecimal("40.0000"),
                        "AUD",
                        TransactionStatus.COMPLETED,
                        new BigDecimal("60.0000")));

        mockMvc.perform(post("/api/accounts/2/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 40.0000,
                                  "currency": "AUD",
                                  "referenceId": "withdrawal-001",
                                  "description": "ATM withdrawal"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(20))
                .andExpect(jsonPath("$.referenceId").value("withdrawal-001"))
                .andExpect(jsonPath("$.accountId").value(2))
                .andExpect(jsonPath("$.amount").value(40.0000))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.balanceAfter").value(60.0000));
    }

    @Test
    void withdrawReturnsConflictForDuplicateReferenceId() throws Exception {
        when(withdrawalService.withdraw(eq(2L), any(WithdrawalRequest.class)))
                .thenThrow(new DuplicateTransactionException("withdrawal-001"));

        mockMvc.perform(post("/api/accounts/2/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 40.0000,
                                  "currency": "AUD",
                                  "referenceId": "withdrawal-001"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSACTION"))
                .andExpect(jsonPath("$.message")
                        .value("Transaction reference already exists: withdrawal-001"));
    }

    @Test
    void withdrawReturnsBadRequestForInvalidAmount() throws Exception {
        mockMvc.perform(post("/api/accounts/2/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 0,
                                  "currency": "AUD",
                                  "referenceId": "withdrawal-001"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }
}
