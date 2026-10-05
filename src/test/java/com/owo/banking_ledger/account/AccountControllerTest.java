package com.owo.banking_ledger.account;

import com.owo.banking_ledger.ObservabilitySliceConfiguration;
import com.owo.banking_ledger.security.ApiSecurityErrorWriter;
import com.owo.banking_ledger.security.SecurityConfig;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

@ActiveProfiles("dev")
@WebMvcTest(AccountController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({
        SecurityConfig.class,
        ApiSecurityErrorWriter.class,
        ObservabilitySliceConfiguration.class })
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
                AccountKind.CUSTOMER,
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
                AccountKind.CUSTOMER,
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
                .andExpect(jsonPath("$.accountKind").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void findAllReturnsPageOfAccounts() throws Exception {
        AccountResponse customerAccount = new AccountResponse(
                2L,
                "ABCDEF1234567890",
                "Alice",
                "AUD",
                AccountKind.CUSTOMER,
                AccountStatus.ACTIVE,
                BigDecimal.ZERO,
                Instant.parse("2026-07-08T00:00:00Z"));

        when(accountService.findAccounts(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(customerAccount),
                        PageRequest.of(0, 20),
                        1));

        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(2))
                .andExpect(jsonPath("$.content[0].accountNumber")
                        .value("ABCDEF1234567890"))
                .andExpect(jsonPath("$.content[0].accountKind").value("CUSTOMER"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void findAllPassesFiltersAndPagingToService() throws Exception {
        when(accountService.findAccounts(any(), any(), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/accounts")
                        .param("ownerSubject", "alice")
                        .param("accountKind", "SYSTEM")
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);
        verify(accountService).findAccounts(
                eq("alice"),
                eq(AccountKind.SYSTEM),
                pageableCaptor.capture());

        org.junit.jupiter.api.Assertions.assertEquals(
                2,
                pageableCaptor.getValue().getPageNumber());
        org.junit.jupiter.api.Assertions.assertEquals(
                5,
                pageableCaptor.getValue().getPageSize());
    }

    @Test
    void findAllReturnsForbiddenWhenListingAnotherOwner() throws Exception {
        when(accountService.findAccounts(eq("bob"), isNull(), any(Pageable.class)))
                .thenThrow(new BusinessException(
                        BusinessErrorCode.ACCESS_DENIED,
                        "Caller is not authorized to list accounts of another owner"));

        mockMvc.perform(get("/api/accounts").param("ownerSubject", "bob"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void findAllReturnsBadRequestForUnknownAccountKind() throws Exception {
        mockMvc.perform(get("/api/accounts").param("accountKind", "SAVINGS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("Invalid value for accountKind: SAVINGS"));

        verifyNoInteractions(accountService);
    }

    @Test
    @WithAnonymousUser
    void findAllReturnsUnauthorizedWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        verifyNoInteractions(accountService);
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
                AccountKind.CUSTOMER,
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
                AccountKind.CUSTOMER,
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
