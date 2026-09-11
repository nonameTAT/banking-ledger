package com.owo.banking_ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Covers the authorization rules themselves. The other integration tests run as
 * an administrator so they can stay focused on ledger behaviour; this one is
 * where the boundaries between callers are actually asserted.
 *
 * <p>Two customers are used throughout: an account is opened by one of them, and
 * the other is expected to be turned away from every route to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AuthorizationIntegrationTest {

    private static final String ADMIN_AUTHORITY = "SCOPE_ledger:admin";

    @Autowired
    private MockMvc mockMvc;

    @Value("${banking.security.dev-jwt-secret}")
    private String devJwtSecret;

    // ---------------------------------------------------------------- 401 ---

    @Test
    void rejectsRequestsWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/accounts/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsATokenThisServiceCannotVerify() throws Exception {
        // Correctly formed and correctly signed, but with the wrong key.
        String foreignToken = signedToken(
                "attacker",
                "ledger:admin",
                "a-secret-this-service-does-not-trust-0123456789");

        mockMvc.perform(get("/api/accounts/1")
                        .header("Authorization", "Bearer " + foreignToken))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Exercises the whole bearer-token path rather than injecting an
     * authentication: the token is signed, parsed, verified, and its scope claim
     * is mapped onto the administrative authority.
     */
    @Test
    void acceptsASignedTokenAndMapsItsScopeToTheAdminAuthority() throws Exception {
        Long accountId = openAccountOwnedBy("scope-mapping-owner");

        String adminToken = signedToken(
                "ops-team",
                "ledger:admin",
                devJwtSecret);

        mockMvc.perform(post("/api/accounts/{id}/freeze", accountId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // ------------------------------------------------------------ ownership ---

    @Test
    void ownerCanReadTheirOwnAccount() throws Exception {
        String owner = subject("owner");
        Long accountId = openAccountOwnedBy(owner);

        mockMvc.perform(get("/api/accounts/{id}", accountId).with(customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));
    }

    @Test
    void otherCustomersCannotReadTheAccount() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(get("/api/accounts/{id}", accountId)
                        .with(customer(subject("stranger"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void otherCustomersCannotDepositIntoTheAccount() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(post("/api/accounts/{id}/deposits", accountId)
                        .with(customer(subject("stranger")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("unauthorized-deposit-" + unique())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void otherCustomersCannotWithdrawFromTheAccount() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(post("/api/accounts/{id}/withdrawals", accountId)
                        .with(customer(subject("stranger")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(depositBody("unauthorized-withdrawal-" + unique())))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherCustomersCannotReadTheAccountLedger() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(get("/api/accounts/{id}/entries", accountId)
                        .with(customer(subject("stranger"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherCustomersCannotReadTheAccountAuditLog() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(get("/api/accounts/{id}/audit-logs", accountId)
                        .with(customer(subject("stranger"))))
                .andExpect(status().isForbidden());
    }

    /**
     * A transfer is authorized against the account the money leaves, so holding
     * the target account is not enough to move someone else's money.
     */
    @Test
    void moneyCannotBePulledOutOfAnAccountTheCallerDoesNotOwn() throws Exception {
        Long victimAccountId = openAccountOwnedBy(subject("victim"));
        String attacker = subject("attacker");
        Long attackerAccountId = openAccountOwnedBy(attacker);

        mockMvc.perform(post("/api/transfers")
                        .with(customer(attacker))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccountId": %d,
                                  "targetAccountId": %d,
                                  "amount": "10.00",
                                  "currency": "AUD",
                                  "referenceId": "%s"
                                }
                                """.formatted(
                                victimAccountId,
                                attackerAccountId,
                                "unauthorized-transfer-" + unique())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ----------------------------------------------------- admin permission ---

    @Test
    void ownersCannotFreezeTheirOwnAccount() throws Exception {
        String owner = subject("owner");
        Long accountId = openAccountOwnedBy(owner);

        mockMvc.perform(post("/api/accounts/{id}/freeze", accountId)
                        .with(customer(owner)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void ownersCannotUnfreezeTheirOwnAccount() throws Exception {
        String owner = subject("owner");
        Long accountId = openAccountOwnedBy(owner);

        mockMvc.perform(post("/api/accounts/{id}/unfreeze", accountId)
                        .with(customer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void customersCannotReverseATransaction() throws Exception {
        mockMvc.perform(post("/api/transactions/{id}/reversals", 1)
                        .with(customer(subject("customer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "referenceId": "%s"
                                }
                                """.formatted("unauthorized-reversal-" + unique())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void administratorsReachAccountsTheyDoNotOwn() throws Exception {
        Long accountId = openAccountOwnedBy(subject("owner"));

        mockMvc.perform(get("/api/accounts/{id}", accountId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));

        mockMvc.perform(post("/api/accounts/{id}/freeze", accountId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
    }

    // ------------------------------------------------------------- helpers ---

    /**
     * Opens an account through the API as the given subject, which is what makes
     * that subject its owner.
     */
    private Long openAccountOwnedBy(String ownerSubject) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .with(customer(ownerSubject))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ownerName": "Owner",
                                  "currency": "AUD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long accountId = ((Number) JsonPath.read(body, "$.id")).longValue();
        assertNotNull(accountId);

        return accountId;
    }

    /**
     * A plain authenticated customer: a token carrying a subject and none of the
     * administrative authority.
     */
    private static RequestPostProcessor customer(String subject) {
        return jwt().jwt(token -> token.subject(subject));
    }

    private static RequestPostProcessor admin() {
        return jwt()
                .jwt(token -> token.subject("ops-team"))
                .authorities(new SimpleGrantedAuthority(ADMIN_AUTHORITY));
    }

    private static String depositBody(String referenceId) {
        return """
                {
                  "amount": "10.00",
                  "currency": "AUD",
                  "referenceId": "%s"
                }
                """.formatted(referenceId);
    }

    /** Subjects are unique per test so accounts never leak between them. */
    private static String subject(String role) {
        return role + "-" + unique();
    }

    private static String unique() {
        return UUID.randomUUID().toString();
    }

    private static String signedToken(
            String subject,
            String scope,
            String secret) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("scope", scope)
                .issueTime(java.util.Date.from(Instant.now()))
                .expirationTime(java.util.Date.from(
                        Instant.now().plus(5, ChronoUnit.MINUTES)))
                .build();

        SignedJWT token = new SignedJWT(
                new JWSHeader(JWSAlgorithm.HS256),
                claims);

        token.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        return token.serialize();
    }
}
