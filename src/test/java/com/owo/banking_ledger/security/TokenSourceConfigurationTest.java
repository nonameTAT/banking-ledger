package com.owo.banking_ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import com.owo.banking_ledger.observability.CurrentTrace;

import io.micrometer.tracing.Tracer;

/**
 * Which token sources a configuration ends up trusting, and which
 * configurations are refused before they can serve a request.
 *
 * <p>The real {@code application.properties} and its profile files are loaded,
 * so these cases describe the configuration the application ships with rather
 * than one assembled for the test. Only the security wiring is started: no
 * database is needed to decide whether the application may start.
 */
class TokenSourceConfigurationTest {

    private static final String ISSUER_URI =
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://auth.example.test/realms/banking";

    private static final String JWK_SET_URI =
            "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://keycloak:8080/realms/banking/protocol/openid-connect/certs";

    private static final String AUDIENCES =
            "spring.security.oauth2.resourceserver.jwt.audiences=banking-ledger-api";

    private static final String DEV_PROFILE = "spring.profiles.active=dev";

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OAuth2ResourceServerAutoConfiguration.class))
            .withUserConfiguration(
                    SecurityConfig.class,
                    ApiSecurityErrorWriter.class,
                    CurrentTrace.class)
            .withBean(Tracer.class, () -> Tracer.NOOP);

    @Test
    void identityProviderSettingsAloneStartWithoutAnHmacDecoder() {
        runner.withPropertyValues(
                        ISSUER_URI,
                        JWK_SET_URI,
                        AUDIENCES,
                        // Read only by the dev profile's configuration, so it must
                        // have no effect here whatever it is set to.
                        "BANKING_DEV_JWT_SECRET=a-secret-that-must-be-ignored-0123456789")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context).doesNotHaveBean("devJwtDecoder");
                    assertThat(context.getEnvironment()
                            .getProperty("banking.security.dev-jwt-secret"))
                            .isNull();
                    assertThat(context.getBean(BankingSecurityProperties.class)
                            .authoritiesClaim())
                            .isEqualTo("ledger_roles");
                });
    }

    @Test
    void theDevProfileAcceptsLocallySignedTokensWithTheScopeClaim() {
        runner.withPropertyValues(DEV_PROFILE)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasBean("devJwtDecoder");
                    assertThat(context.getBean(BankingSecurityProperties.class)
                            .authoritiesClaim())
                            .isEqualTo("scope");
                });
    }

    @Test
    void theDevProfileTogetherWithAnIssuerIsRefused() {
        runner.withPropertyValues(DEV_PROFILE, ISSUER_URI)
                .run(failsWith("Both banking.security.dev-jwt-secret and an OAuth2"));
    }

    @Test
    void theDevProfileTogetherWithAJwkSetUriIsRefused() {
        runner.withPropertyValues(DEV_PROFILE, JWK_SET_URI)
                .run(failsWith("Both banking.security.dev-jwt-secret and an OAuth2"));
    }

    @Test
    void aDevSecretNextToIdentityProviderSettingsIsRefusedEvenWithoutTheProfile() {
        runner.withPropertyValues(
                        "banking.security.dev-jwt-secret=a-secret-set-by-mistake-0123456789",
                        ISSUER_URI,
                        JWK_SET_URI,
                        AUDIENCES)
                .run(failsWith("Both banking.security.dev-jwt-secret and an OAuth2"));
    }

    @Test
    void theDevProfileWithABlankSecretIsRefused() {
        runner.withPropertyValues(DEV_PROFILE, "BANKING_DEV_JWT_SECRET=")
                .run(failsWith("banking.security.dev-jwt-secret is empty"));
    }

    @Test
    void noTokenSourceAtAllIsRefusedWithAnExplanation() {
        runner.run(failsWith("No token decoder is configured"));
    }

    private static ContextConsumer<AssertableWebApplicationContext> failsWith(
            String message) {
        return context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .hasMessageContaining(message);
        };
    }
}
