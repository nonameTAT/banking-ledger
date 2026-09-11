package com.owo.banking_ledger.audit;

import com.owo.banking_ledger.security.ApiSecurityErrorWriter;
import com.owo.banking_ledger.security.SecurityConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.account.AccountNotFoundException;

@WebMvcTest(AuditLogController.class)
// The real chain is imported rather than the test default: it is what
// disables CSRF for these token-authenticated endpoints, so a POST here
// behaves the way it does in the running application.
@Import({ SecurityConfig.class, ApiSecurityErrorWriter.class })
@WithMockUser
class AuditLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogService auditLogService;

    @Test
    void findAuditLogsReturnsAccountAuditLogs() throws Exception {
        when(auditLogService.findAccountAuditLogs(eq(2L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(new AuditLogResponse(
                        100L,
                        AuditAction.DEPOSIT_COMPLETED,
                        2L,
                        null,
                        10L,
                        "deposit-001",
                        new BigDecimal("100.0000"),
                        "AUD",
                        "Initial deposit",
                        Instant.parse("2026-07-08T00:00:00Z")))));

        mockMvc.perform(get("/api/accounts/2/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100))
                .andExpect(jsonPath("$.content[0].action").value("DEPOSIT_COMPLETED"))
                .andExpect(jsonPath("$.content[0].accountId").value(2))
                .andExpect(jsonPath("$.content[0].transactionId").value(10))
                .andExpect(jsonPath("$.content[0].referenceId").value("deposit-001"))
                .andExpect(jsonPath("$.content[0].amount").value(100.0000))
                .andExpect(jsonPath("$.content[0].currency").value("AUD"))
                .andExpect(jsonPath("$.content[0].details").value("Initial deposit"))
                .andExpect(jsonPath("$.content[0].createdAt")
                        .value("2026-07-08T00:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void findAuditLogsPassesPaginationToService() throws Exception {
        when(auditLogService.findAccountAuditLogs(eq(2L), any(Pageable.class)))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/accounts/2/audit-logs")
                        .param("page", "1")
                        .param("size", "2")
                        .param("sort", "id,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        ArgumentCaptor<Pageable> pageableCaptor =
                ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogService)
                .findAccountAuditLogs(eq(2L), pageableCaptor.capture());
        Pageable pageable = pageableCaptor.getValue();

        assertEquals(1, pageable.getPageNumber());
        assertEquals(2, pageable.getPageSize());
        assertEquals("id: ASC", pageable.getSort().toString());
    }

    @Test
    void findAuditLogsReturnsNotFoundWhenAccountDoesNotExist() throws Exception {
        when(auditLogService.findAccountAuditLogs(eq(99L), any(Pageable.class)))
                .thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(get("/api/accounts/99/audit-logs"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: 99"));
    }
}
