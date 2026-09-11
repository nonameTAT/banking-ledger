package com.owo.banking_ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.owo.banking_ledger.observability.LedgerMetrics;
import com.owo.banking_ledger.reconciliation.ReconciliationDifference;
import com.owo.banking_ledger.reconciliation.ReconciliationRun;
import com.owo.banking_ledger.reconciliation.ReconciliationService;

import org.springframework.data.domain.PageRequest;

/**
 * Reconciliation exists to notice a defect that would otherwise be silent: a
 * stored balance drifting away from the entries behind it. Asserting that a
 * healthy ledger reconciles proves very little on its own, so these tests also
 * introduce real drift, by writing a balance straight to the table the way a
 * bug would, and check that it is caught, recorded, and reportable.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ReconciliationIntegrationTest {

    private static final String ADMIN_AUTHORITY = "SCOPE_ledger:admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private LedgerMetrics metrics;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aCorrectlyPostedAccountIsNotReported() throws Exception {
        Long accountId = openAccountWithDeposit("100.00");

        ReconciliationRun run = reconciliationService.reconcile();

        assertTrue(run.getAccountsChecked() > 0);
        assertNotNull(run.getId());
        assertFalse(
                reportedAccountIds(run).contains(accountId),
                "a correctly posted account should not be reported");
    }

    @Test
    void aBalanceThatDriftsFromItsEntriesIsCaught() throws Exception {
        Long accountId = openAccountWithDeposit("100.00");

        // What a defect would leave behind: the materialized balance changed
        // without the entries that justify it. Ledger entries are append-only
        // and are deliberately left untouched, so the derived balance stays the
        // truthful one.
        driftBalance(accountId, new BigDecimal("999.00"));

        try {
            ReconciliationRun run = reconciliationService.reconcile();

            assertTrue(run.getDifferenceCount() > 0);
            assertTrue(reportedAccountIds(run).contains(accountId));

            ReconciliationDifference difference = differenceFor(run, accountId);
            assertEquals(0, new BigDecimal("999.00")
                    .compareTo(difference.getRecordedBalance()));
            assertEquals(0, new BigDecimal("100.00")
                    .compareTo(difference.getDerivedBalance()));
            assertEquals(0, new BigDecimal("899.00")
                    .compareTo(difference.getDifference()));

            assertTrue(metrics.currentDifferences() > 0);
        } finally {
            // Leave the ledger consistent for whatever runs next.
            driftBalance(accountId, new BigDecimal("100.00"));
        }

        ReconciliationRun afterRepair = reconciliationService.reconcile();
        assertFalse(reportedAccountIds(afterRepair).contains(accountId));
    }

    /**
     * Reconciliation reports; it never writes a balance back. Correcting one
     * automatically would erase the evidence of the defect that caused it.
     */
    @Test
    void reconciliationDoesNotRepairTheBalanceItReports() throws Exception {
        Long accountId = openAccountWithDeposit("100.00");
        driftBalance(accountId, new BigDecimal("777.00"));

        try {
            reconciliationService.reconcile();

            assertEquals(
                    0,
                    new BigDecimal("777.00").compareTo(storedBalance(accountId)));
        } finally {
            driftBalance(accountId, new BigDecimal("100.00"));
        }
    }

    @Test
    void everyRunIsRecordedEvenWhenNothingIsWrong() {
        long before = runCount();

        reconciliationService.reconcile();

        // A clean run still has to be stored, otherwise "no differences" and
        // "reconciliation stopped running" look identical.
        assertEquals(before + 1, runCount());
    }

    @Test
    void administratorsCanQueryRunsAndDifferences() throws Exception {
        reconciliationService.reconcile();

        mockMvc.perform(get("/api/reconciliation/runs").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].accountsChecked").exists());

        mockMvc.perform(get("/api/reconciliation/differences").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void administratorsCanTriggerARunOnDemand() throws Exception {
        mockMvc.perform(post("/api/reconciliation/runs").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.accountsChecked").exists());
    }

    @Test
    void reconciliationIsNotReadableByCustomers() throws Exception {
        RequestPostProcessor customer = jwt().jwt(token -> token.subject("customer"));

        mockMvc.perform(get("/api/reconciliation/runs").with(customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/reconciliation/differences").with(customer))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/reconciliation/runs").with(customer))
                .andExpect(status().isForbidden());
    }

    @Test
    void reconciliationRequiresAToken() throws Exception {
        mockMvc.perform(get("/api/reconciliation/runs"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------ helpers ---

    private List<Long> reportedAccountIds(ReconciliationRun run) {
        return reconciliationService
                .findDifferences(null, PageRequest.of(0, 200))
                .getContent()
                .stream()
                .filter(difference -> difference.getRunId().equals(run.getId()))
                .map(ReconciliationDifference::getAccountId)
                .toList();
    }

    private ReconciliationDifference differenceFor(
            ReconciliationRun run,
            Long accountId) {
        return reconciliationService
                .findDifferences(accountId, PageRequest.of(0, 20))
                .getContent()
                .stream()
                .filter(difference -> difference.getRunId().equals(run.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no difference recorded for account " + accountId));
    }

    private void driftBalance(Long accountId, BigDecimal balance) {
        jdbcTemplate.update(
                "UPDATE accounts SET balance = ? WHERE id = ?",
                balance,
                accountId);
    }

    private BigDecimal storedBalance(Long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM accounts WHERE id = ?",
                BigDecimal.class,
                accountId);
    }

    private long runCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_runs",
                Long.class);
    }

    private Long openAccountWithDeposit(String amount) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Reconciliation",
                                  "currency": "AUD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long accountId = ((Number) JsonPath.read(body, "$.id")).longValue();

        mockMvc.perform(post("/api/accounts/{id}/deposits", accountId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "%s",
                                  "currency": "AUD",
                                  "referenceId": "%s"
                                }
                                """.formatted(
                                amount,
                                "reconciliation-" + UUID.randomUUID())))
                .andExpect(status().isCreated());

        return accountId;
    }

    private static RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("reconciliation-admin"))
                .authorities(new SimpleGrantedAuthority(ADMIN_AUTHORITY));
    }
}
