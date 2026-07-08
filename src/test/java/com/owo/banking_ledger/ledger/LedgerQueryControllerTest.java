package com.owo.banking_ledger.ledger;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.owo.banking_ledger.account.AccountNotFoundException;

@WebMvcTest(LedgerQueryController.class)
class LedgerQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerQueryService ledgerQueryService;

    @Test
    void findEntriesReturnsAccountLedgerEntries() throws Exception {
        when(ledgerQueryService.findAccountEntries(2L))
                .thenReturn(List.of(new LedgerEntryResponse(
                        100L,
                        10L,
                        "deposit-001",
                        TransactionType.DEPOSIT,
                        EntryType.CREDIT,
                        new BigDecimal("100.0000"),
                        new BigDecimal("100.0000"),
                        Instant.parse("2026-07-08T00:00:00Z"))));

        mockMvc.perform(get("/api/accounts/2/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(100))
                .andExpect(jsonPath("$[0].transactionId").value(10))
                .andExpect(jsonPath("$[0].referenceId").value("deposit-001"))
                .andExpect(jsonPath("$[0].transactionType").value("DEPOSIT"))
                .andExpect(jsonPath("$[0].entryType").value("CREDIT"))
                .andExpect(jsonPath("$[0].amount").value(100.0000))
                .andExpect(jsonPath("$[0].balanceAfter").value(100.0000))
                .andExpect(jsonPath("$[0].createdAt").value("2026-07-08T00:00:00Z"));
    }

    @Test
    void findEntriesReturnsNotFoundWhenAccountDoesNotExist() throws Exception {
        when(ledgerQueryService.findAccountEntries(99L))
                .thenThrow(new AccountNotFoundException(99L));

        mockMvc.perform(get("/api/accounts/99/entries"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Account not found: 99"));
    }
}
