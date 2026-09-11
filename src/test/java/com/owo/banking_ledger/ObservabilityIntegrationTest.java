package com.owo.banking_ledger;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.owo.banking_ledger.observability.LedgerMetrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;

/**
 * Covers what an operator relies on when something goes wrong: that a request
 * can be followed into the logs by id, and that failures are counted somewhere
 * an alert can read them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ObservabilityIntegrationTest {

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String ADMIN_AUTHORITY = "SCOPE_ledger:admin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    // ------------------------------------------------------------- traces ---

    @Test
    void everyResponseCarriesTheTraceIdOfItsRequest() throws Exception {
        Long accountId = openAccount();

        String traceId = mockMvc.perform(get("/api/accounts/{id}", accountId).with(admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(TRACE_ID_HEADER);

        assertNotNull(traceId, "successful responses should carry a trace id");
        assertTrue(traceId.matches("[0-9a-f]+"), "unexpected trace id: " + traceId);
    }

    /**
     * The id in the body has to be the id of the request that produced it,
     * otherwise quoting it from an error message leads to the wrong logs.
     */
    @Test
    void anErrorCarriesTheSameTraceIdInItsBodyAndItsHeader() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/{id}", 999_999_999L).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        String headerTraceId = result.getResponse().getHeader(TRACE_ID_HEADER);
        String bodyTraceId = JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.traceId");

        assertNotNull(headerTraceId);
        assertEquals(headerTraceId, bodyTraceId);
    }

    @Test
    void rejectedRequestsCarryATraceIdTooEvenWithoutAToken() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/accounts/{id}", 1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andReturn();

        assertNotNull(result.getResponse().getHeader(TRACE_ID_HEADER));
    }

    @Test
    void separateRequestsGetSeparateTraceIds() throws Exception {
        Long accountId = openAccount();

        String first = traceIdOfAccountRead(accountId);
        String second = traceIdOfAccountRead(accountId);

        assertNotEquals(first, second);
    }

    // ------------------------------------------------------------ metrics ---

    @Test
    void failedMoneyMovementIsCounted() throws Exception {
        Long accountId = openAccount();
        double before = counterTotal(LedgerMetrics.TRANSACTION_ERRORS);

        // Withdrawing from an account with no money is a rejection the ledger
        // is supposed to produce, and it is still a transaction that did not
        // complete, so it must show up in the counter.
        mockMvc.perform(post("/api/accounts/{id}/withdrawals", accountId)
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "100.00",
                                  "currency": "AUD",
                                  "referenceId": "%s"
                                }
                                """.formatted("observability-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest());

        assertEquals(
                before + 1,
                counterTotal(LedgerMetrics.TRANSACTION_ERRORS),
                0.0001);
    }

    /**
     * Authorization refusals are deliberately kept out of the transaction error
     * signal: a customer reaching for the wrong account is not the ledger
     * failing, and alerting on it would bury real faults.
     */
    @Test
    void authorizationRefusalsAreNotCountedAsTransactionErrors() throws Exception {
        Long accountId = openAccount();
        double before = counterTotal(LedgerMetrics.TRANSACTION_ERRORS);

        mockMvc.perform(get("/api/accounts/{id}", accountId)
                        .with(jwt().jwt(token -> token.subject("stranger"))))
                .andExpect(status().isForbidden());

        assertEquals(before, counterTotal(LedgerMetrics.TRANSACTION_ERRORS), 0.0001);
    }

    /**
     * The scrape must already carry these series, at zero, before anything has
     * gone wrong. An alert on the first database failure depends on it: with no
     * earlier sample, increase() cannot see the step up to one.
     */
    @Test
    void failureSeriesAreScrapableAtZeroBeforeAnyFailure() throws Exception {
        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(
                scrape.contains("banking_database_failures_total{reason=\"connection\"}"),
                "the database failure series must exist before a failure happens");
        assertTrue(
                scrape.contains("banking_reconciliation_failures_total"),
                "the reconciliation failure series must exist before a failure happens");
        assertTrue(
                scrape.contains("banking_reconciliation_last_success_timestamp"),
                "the last-success gauge must always be published");
    }

    @Test
    void metricsAreExposedForScraping() throws Exception {
        // Produce at least one error so the counter exists in the registry.
        failOneWithdrawal();

        String scrape = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(
                scrape.contains("banking_transaction_errors_total"),
                "scrape should expose the transaction error counter");
        assertTrue(
                scrape.contains("banking_reconciliation_differences"),
                "scrape should expose the reconciliation gauge");
    }

    // ---------------------------------------------- operational endpoints ---

    @Test
    void healthAndScrapeAreReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }

    @Test
    void otherActuatorEndpointsRequireTheAdministrativePermission() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().jwt(token -> token.subject("customer"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/metrics").with(admin()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ helpers ---

    private String traceIdOfAccountRead(Long accountId) throws Exception {
        return mockMvc.perform(get("/api/accounts/{id}", accountId).with(admin()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getHeader(TRACE_ID_HEADER);
    }

    private void failOneWithdrawal() throws Exception {
        mockMvc.perform(post("/api/accounts/{id}/withdrawals", openAccount())
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "100.00",
                                  "currency": "AUD",
                                  "referenceId": "%s"
                                }
                                """.formatted("observability-" + UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    private double counterTotal(String name) {
        return Search.in(meterRegistry)
                .name(name)
                .counters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private Long openAccount() throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Observability",
                                  "currency": "AUD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("observability-admin"))
                .authorities(new SimpleGrantedAuthority(ADMIN_AUTHORITY));
    }
}
