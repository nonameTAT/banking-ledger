package com.owo.banking_ledger.account;

import com.owo.banking_ledger.security.ApiSecurityErrorWriter;
import com.owo.banking_ledger.security.SecurityConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.common.BusinessException;

@WebMvcTest(AccountController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({ SecurityConfig.class, ApiSecurityErrorWriter.class })
@WithMockUser
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @Test
    void createReturnsCreatedAccount() throws Exception {
        AccountResponse response = new AccountResponse(
                1L,
                "ABCDEF1234567890",
                "Alice",
                "AUD",
                AccountStatus.ACTIVE,
                BigDecimal.ZERO,
                Instant.parse("2026-07-08T00:00:00Z"));

        when(accountService.create(any(CreateAccountRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Alice",
                                  "currency": "AUD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountNumber").value("ABCDEF1234567890"))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0));

        ArgumentCaptor<CreateAccountRequest> requestCaptor =
                ArgumentCaptor.forClass(CreateAccountRequest.class);
        verify(accountService).create(requestCaptor.capture());
        CreateAccountRequest request = requestCaptor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals("Alice", request.ownerName());
        org.junit.jupiter.api.Assertions.assertEquals("AUD", request.currency());
    }

    @Test
    void createReturnsBadRequestForInvalidCurrency() throws Exception {
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Alice",
                                  "currency": "aud"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturnsBadRequestForUnsupportedCurrency() throws Exception {
        when(accountService.create(any(CreateAccountRequest.class)))
                .thenThrow(BusinessException.invalidRequest(
                        "Currency is not supported: USD"));

        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Alice",
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Currency is not supported: USD"));
    }

    @Test
    void findByIdReturnsAccount() throws Exception {
        AccountResponse response = new AccountResponse(
                1L,
                "ABCDEF1234567890",
                "Alice",
                "AUD",
                AccountStatus.ACTIVE,
                BigDecimal.ZERO,
                Instant.parse("2026-07-08T00:00:00Z"));

        when(accountService.findById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountNumber").value("ABCDEF1234567890"))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.currency").value("AUD"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void findByIdReturnsNotFoundWhenAccountDoesNotExist() throws Exception {
        when(accountService.findById(99L))
                .thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(get("/api/accounts/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: 99"));
    }

    @Test
    void freezeReturnsFrozenAccount() throws Exception {
        AccountResponse response = new AccountResponse(
                1L,
                "ABCDEF1234567890",
                "Alice",
                "AUD",
                AccountStatus.FROZEN,
                BigDecimal.ZERO,
                Instant.parse("2026-07-08T00:00:00Z"));

        when(accountService.freeze(1L)).thenReturn(response);

        mockMvc.perform(post("/api/accounts/1/freeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    @Test
    void unfreezeReturnsActiveAccount() throws Exception {
        AccountResponse response = new AccountResponse(
                1L,
                "ABCDEF1234567890",
                "Alice",
                "AUD",
                AccountStatus.ACTIVE,
                BigDecimal.ZERO,
                Instant.parse("2026-07-08T00:00:00Z"));

        when(accountService.unfreeze(1L)).thenReturn(response);

        mockMvc.perform(post("/api/accounts/1/unfreeze"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void freezeReturnsBadRequestForSystemAccount() throws Exception {
        when(accountService.freeze(1L))
                .thenThrow(BusinessException.invalidRequest(
                        "Only customer accounts can be frozen"));

        mockMvc.perform(post("/api/accounts/1/freeze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Only customer accounts can be frozen"));
    }

    @Test
    void unfreezeReturnsBadRequestForSystemAccount() throws Exception {
        when(accountService.unfreeze(1L))
                .thenThrow(BusinessException.invalidRequest(
                        "Only customer accounts can be unfrozen"));

        mockMvc.perform(post("/api/accounts/1/unfreeze"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Only customer accounts can be unfrozen"));
    }
}
