package com.owo.banking_ledger.reversal;

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

import com.owo.banking_ledger.ledger.ReversalNotAllowedException;
import com.owo.banking_ledger.ledger.TransactionNotFoundException;
import com.owo.banking_ledger.ledger.TransactionStatus;

@WebMvcTest(ReversalController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({ SecurityConfig.class, ApiSecurityErrorWriter.class })
@WithMockUser
class ReversalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReversalService reversalService;

    @Test
    void reverseReturnsCreatedReversal() throws Exception {
        when(reversalService.reverse(eq(10L), any(ReversalRequest.class)))
                .thenReturn(new ReversalResponse(
                        20L,
                        "reversal-001",
                        10L,
                        "deposit-001",
                        new BigDecimal("100.00"),
                        "AUD",
                        TransactionStatus.COMPLETED));

        mockMvc.perform(post("/api/transactions/10/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "referenceId": "reversal-001",
                                  "description": "Duplicate deposit"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value(20))
                .andExpect(jsonPath("$.referenceId").value("reversal-001"))
                .andExpect(jsonPath("$.originalTransactionId").value(10))
                .andExpect(jsonPath("$.originalReferenceId").value("deposit-001"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void reverseReturnsConflictWhenTheTransactionCannotBeReversed() throws Exception {
        when(reversalService.reverse(eq(10L), any(ReversalRequest.class)))
                .thenThrow(new ReversalNotAllowedException(
                        "Transaction has already been reversed: deposit-001"));

        mockMvc.perform(post("/api/transactions/10/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "referenceId": "reversal-002"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVERSAL_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message")
                        .value("Transaction has already been reversed: deposit-001"));
    }

    @Test
    void reverseReturnsNotFoundForAnUnknownTransaction() throws Exception {
        when(reversalService.reverse(eq(99L), any(ReversalRequest.class)))
                .thenThrow(new TransactionNotFoundException(99L));

        mockMvc.perform(post("/api/transactions/99/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "referenceId": "reversal-003"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Transaction not found: 99"));
    }

    @Test
    void reverseRequiresAReferenceId() throws Exception {
        mockMvc.perform(post("/api/transactions/10/reversals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "description": "Missing reference"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
