package com.owo.banking_ledger.transfer;

import com.owo.banking_ledger.ObservabilitySliceConfiguration;
import com.owo.banking_ledger.security.ApiSecurityErrorWriter;
import com.owo.banking_ledger.security.SecurityConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.common.BusinessException;
import com.owo.banking_ledger.deposit.DuplicateTransactionException;

@WebMvcTest(TransferController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({
        SecurityConfig.class,
        ApiSecurityErrorWriter.class,
        ObservabilitySliceConfiguration.class })
@WithMockUser
class TransferControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransferService transferService;

    @Test
    void transferReturnsConflictForDuplicateReferenceId() throws Exception {
        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(new DuplicateTransactionException("transfer-001"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": 2,
                                  "targetAccountId": 4,
                                  "amount": "20.00",
                                  "currency": "AUD",
                                  "referenceId": "transfer-001",
                                  "description": "Duplicate transfer"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_TRANSACTION"))
                .andExpect(jsonPath("$.message")
                        .value("Transaction reference already exists: transfer-001"));
    }

    @Test
    void transferReturnsBadRequestForInsufficientBalance() throws Exception {
        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(BusinessException.invalidRequest("Insufficient balance"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": 2,
                                  "targetAccountId": 4,
                                  "amount": "999.00",
                                  "currency": "AUD",
                                  "referenceId": "transfer-insufficient-001",
                                  "description": "Insufficient balance test"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Insufficient balance"));
    }

    @Test
    void transferReturnsBadRequestForSameSourceAndTargetAccount() throws Exception {
        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(BusinessException.invalidRequest(
                        "Source and target accounts must be different"));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": 2,
                                  "targetAccountId": 2,
                                  "amount": "10.00",
                                  "currency": "AUD",
                                  "referenceId": "transfer-self-001",
                                  "description": "Self transfer test"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Source and target accounts must be different"));
    }

    @Test
    void transferReturnsNotFoundForMissingAccount() throws Exception {
        when(transferService.transfer(any(TransferRequest.class)))
                .thenThrow(new AccountNotFoundException(99999L));

        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": 99999,
                                  "targetAccountId": 4,
                                  "amount": "10.00",
                                  "currency": "AUD",
                                  "referenceId": "transfer-missing-001",
                                  "description": "Missing account test"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: 99999"));
    }

    @Test
    void transferReturnsBadRequestForInvalidAmount() throws Exception {
        mockMvc.perform(post("/api/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": 2,
                                  "targetAccountId": 4,
                                  "amount": "0",
                                  "currency": "AUD",
                                  "referenceId": "transfer-invalid-amount-001",
                                  "description": "Invalid amount test"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(transferService, never()).transfer(any(TransferRequest.class));
    }
}
