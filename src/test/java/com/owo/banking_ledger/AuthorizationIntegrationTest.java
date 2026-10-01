package com.owo.banking_ledger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
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
    void rejectsAccountListingWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/accounts"))
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

    // -------------------------------------------------------------- listing ---

    @Test
    void customersListExactlyTheirOwnAccounts() throws Exception {
        String owner = subject("owner");
        Long firstAccountId = openAccountOwnedBy(owner);
        Long secondAccountId = openAccountOwnedBy(owner);
        openAccountOwnedBy(subject("stranger"));

        mockMvc.perform(get("/api/accounts").with(customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].id").value(contains(
                        firstAccountId.intValue(),
                        secondAccountId.intValue())))
                .andExpect(jsonPath("$.content[*].accountKind")
                        .value(everyItem(is("CUSTOMER"))));

        // Naming themselves as the owner is the same listing, not a refusal.
        mockMvc.perform(get("/api/accounts")
                        .param("ownerSubject", owner)
                        .with(customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void customersCannotListAnotherOwnersAccounts() throws Exception {
        String victim = subject("victim");
        openAccountOwnedBy(victim);

        mockMvc.perform(get("/api/accounts")
                        .param("ownerSubject", victim)
                        .with(customer(subject("stranger"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void customersNeverSeeSystemAccounts() throws Exception {
        String owner = subject("owner");
        openAccountOwnedBy(owner);

        mockMvc.perform(get("/api/accounts")
                        .param("accountKind", "SYSTEM")
                        .with(customer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Other tests share this database, so the full listing is asserted through
     * its ordering rather than its size: ids only grow, so the seeded cash
     * account opens the first page and the two accounts opened here, by
     * different owners, close the last one.
     */
    @Test
    void administratorsListEveryAccountIncludingSystemAccounts() throws Exception {
        Long firstAccountId = openAccountOwnedBy(subject("owner"));
        Long secondAccountId = openAccountOwnedBy(subject("other-owner"));

        String firstPage = mockMvc.perform(get("/api/accounts")
                        .param("size", "1")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].accountNumber")
                        .value("SYSTEM-CASH-AUD"))
                .andExpect(jsonPath("$.content[0].accountKind").value("SYSTEM"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        long total = ((Number) JsonPath.read(firstPage, "$.totalElements"))
                .longValue();

        mockMvc.perform(get("/api/accounts")
                        .param("size", "1")
                        .param("page", String.valueOf(total - 2))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(firstAccountId));

        mockMvc.perform(get("/api/accounts")
                        .param("size", "1")
                        .param("page", String.valueOf(total - 1))
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(secondAccountId));
    }

    @Test
    void administratorsFilterByOwnerAndByKind() throws Exception {
        String owner = subject("owner");
        Long accountId = openAccountOwnedBy(owner);
        openAccountOwnedBy(subject("other-owner"));

        mockMvc.perform(get("/api/accounts")
                        .param("ownerSubject", owner)
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(accountId));

        mockMvc.perform(get("/api/accounts")
                        .param("accountKind", "SYSTEM")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].accountKind")
                        .value(everyItem(is("SYSTEM"))))
                .andExpect(jsonPath("$.content[*].accountNumber")
                        .value(hasItem("SYSTEM-CASH-AUD")));

        mockMvc.perform(get("/api/accounts")
                        .param("ownerSubject", owner)
                        .param("accountKind", "SYSTEM")
                        .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
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
