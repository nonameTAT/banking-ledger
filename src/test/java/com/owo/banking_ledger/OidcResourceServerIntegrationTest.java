package com.owo.banking_ledger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

/**
 * The production token path: identity provider settings only, no development
 * profile.
 *
 * <p>Keycloak itself is not started. A local endpoint publishes the public half
 * of a key this test signs with, the way Keycloak publishes its JWKS on the
 * internal network, and the tokens carry the claims Keycloak's mappers produce:
 * the public issuer in {@code iss}, the API client in {@code aud}, and the
 * caller's roles in {@code ledger_roles}. That proves the application's side of
 * the contract. Whether a real Keycloak issues exactly these claims is checked
 * against the running stack.
 */
@SpringBootTest(properties =
        // Read only by the dev profile's configuration; set here to show it
        // cannot switch locally signed tokens back on.
        "BANKING_DEV_JWT_SECRET=local-development-only-secret-do-not-deploy")
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class OidcResourceServerIntegrationTest {

    private static final String ISSUER = "https://auth.example.test/realms/banking";
    private static final String API_CLIENT = "banking-ledger-api";
    private static final String DEV_SECRET = "local-development-only-secret-do-not-deploy";

    private static final RSAKey SIGNING_KEY = generateKey("keycloak-key");
    private static final HttpServer JWKS_ENDPOINT = startJwksEndpoint(SIGNING_KEY);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + JWKS_ENDPOINT.getAddress().getPort()
                        + "/realms/banking/protocol/openid-connect/certs");
        registry.add("spring.security.oauth2.resourceserver.jwt.audiences",
                () -> API_CLIENT);
    }

    @AfterAll
    static void stopJwksEndpoint() {
        JWKS_ENDPOINT.stop(0);
    }

    @Test
    void startsWithIdentityProviderSettingsAloneAndNoHmacDecoder() {
        assertFalse(context.containsBean("devJwtDecoder"));
        assertEquals(1, context.getBeansOfType(JwtDecoder.class).size());
    }

    @Test
    void customersReadTheirOwnAccount() throws Exception {
        String customer = customerToken(subject());
        Long accountId = openAccount(customer);

        mockMvc.perform(get("/api/accounts/{id}", accountId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId));
    }

    /**
     * Only {@code ledger_roles} grants administration now. A {@code scope}
     * claim, which is what made a development token an administrator, carries
     * no weight.
     */
    @Test
    void customersAreRefusedAdministrativeOperations() throws Exception {
        String customer = token(new JWTClaimsSet.Builder()
                .subject(subject())
                .claim("ledger_roles", List.of())
                .claim("scope", "ledger:admin"));
        Long accountId = openAccount(customer);

        mockMvc.perform(post("/api/accounts/{id}/freeze", accountId)
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mockMvc.perform(post("/api/transactions/{id}/reversals", 1)
                        .header("Authorization", "Bearer " + customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void administratorsPerformTheSameOperations() throws Exception {
        String admin = adminToken();
        Long accountId = openAccount(customerToken(subject()));

        String deposit = mockMvc.perform(post("/api/accounts/{id}/deposits", accountId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": "10.00",
                                  "currency": "AUD",
                                  "referenceId": "oidc-deposit-%s"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long transactionId = ((Number) JsonPath.read(deposit, "$.transactionId"))
                .longValue();

        mockMvc.perform(post("/api/transactions/{id}/reversals", transactionId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reversalBody()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/accounts/{id}/freeze", accountId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(get("/api/reconciliation/runs")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void tokensFromAnotherIssuerAreRejected() throws Exception {
        String token = token(new JWTClaimsSet.Builder()
                .subject(subject())
                .issuer("https://auth.example.test/realms/another-realm"));

        assertUnauthenticated(token);
    }

    @Test
    void tokensIssuedForAnotherAudienceAreRejected() throws Exception {
        String token = token(new JWTClaimsSet.Builder()
                .subject(subject())
                .audience("some-other-client"));

        assertUnauthenticated(token);
    }

    @Test
    void tokensWithoutAnAudienceAreRejected() throws Exception {
        String token = token(new JWTClaimsSet.Builder()
                .subject(subject())
                .audience(List.of()));

        assertUnauthenticated(token);
    }

    @Test
    void tokensSignedWithAnotherKeyAreRejected() throws Exception {
        String token = sign(
                generateKey("keycloak-key"),
                claims(new JWTClaimsSet.Builder().subject(subject())));

        assertUnauthenticated(token);
    }

    /** What {@code scripts/dev-token.sh} mints, here pointed at the right issuer. */
    @Test
    void developmentTokensAreRejected() throws Exception {
        JWTClaimsSet claims = claims(new JWTClaimsSet.Builder()
                .subject("ops-team")
                .claim("scope", "ledger:admin")
                .claim("ledger_roles", List.of("ledger:admin")));
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        token.sign(new MACSigner(DEV_SECRET.getBytes(StandardCharsets.UTF_8)));

        assertUnauthenticated(token.serialize());
    }

    // ------------------------------------------------------------- helpers ---

    private void assertUnauthenticated(String token) throws Exception {
        mockMvc.perform(get("/api/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    private Long openAccount(String token) throws Exception {
        String body = mockMvc.perform(post("/api/accounts")
                        .header("Authorization", "Bearer " + token)
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

        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** A customer: Keycloak may omit {@code ledger_roles} when there are none. */
    private static String customerToken(String subject) throws Exception {
        return token(new JWTClaimsSet.Builder().subject(subject));
    }

    private static String adminToken() throws Exception {
        return token(new JWTClaimsSet.Builder()
                .subject(subject())
                .claim("ledger_roles", List.of("ledger:admin")));
    }

    /** Signs the given claims, filling in whatever Keycloak would and they lack. */
    private static String token(JWTClaimsSet.Builder claims) throws Exception {
        return sign(SIGNING_KEY, claims(claims));
    }

    private static JWTClaimsSet claims(JWTClaimsSet.Builder builder) {
        JWTClaimsSet given = builder.build();
        Instant now = Instant.now();

        if (given.getIssuer() == null) {
            builder.issuer(ISSUER);
        }
        if (given.getAudience().isEmpty() && given.getClaim("aud") == null) {
            builder.audience(API_CLIENT);
        }

        return builder
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                .build();
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) throws Exception {
        JWSSigner signer = new RSASSASigner(key);
        SignedJWT token = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256)
                        .type(JOSEObjectType.JWT)
                        .keyID(key.getKeyID())
                        .build(),
                claims);
        token.sign(signer);
        return token.serialize();
    }

    private static String reversalBody() {
        return """
                {
                  "referenceId": "oidc-reversal-%s"
                }
                """.formatted(UUID.randomUUID());
    }

    private static String subject() {
        return UUID.randomUUID().toString();
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer startJwksEndpoint(RSAKey key) {
        try {
            byte[] jwks = new JWKSet(key.toPublicJWK())
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/realms/banking/protocol/openid-connect/certs",
                    exchange -> {
                        exchange.getResponseHeaders()
                                .add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, jwks.length);
                        try (OutputStream body = exchange.getResponseBody()) {
                            body.write(jwks);
                        }
                    });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
