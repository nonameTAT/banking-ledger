package com.owo.banking_ledger.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Authenticates every API request against a bearer token.
 *
 * <p>Tokens are verified, never issued, here. Outside development they come
 * from Keycloak: {@code issuer-uri} names the public issuer every token must
 * carry, {@code jwk-set-uri} the internal address its signing keys are fetched
 * from, and {@code audiences} the API client a token must be issued for. Spring
 * Boot builds that decoder. The only other source of tokens is
 * {@link #devJwtDecoder}, which exists under the {@value #DEV_PROFILE} profile
 * alone.
 *
 * <p>Only authentication is enforced at this layer. Which accounts a caller may
 * touch is decided by {@link AccountAccessPolicy} inside the services, so the
 * rules apply no matter which endpoint leads there.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(BankingSecurityProperties.class)
public class SecurityConfig {

    private static final Log logger = LogFactory.getLog(SecurityConfig.class);

    /** The profile that accepts tokens signed with the local development secret. */
    public static final String DEV_PROFILE = "dev";

    /**
     * Documentation describes the API rather than exposing account data, so it
     * stays reachable without a token; everything else requires one.
     */
    private static final String[] PUBLIC_PATHS = {
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    /**
     * Liveness and the metrics scrape are left open because the things that
     * consume them, a load balancer and Prometheus, generally cannot hold a
     * token from the identity provider. They expose operational counts rather
     * than account data, and a deployment should still keep them on an internal
     * network or behind the ingress. Every other actuator endpoint needs the
     * administrative permission.
     */
    private static final String[] PUBLIC_OPERATIONAL_PATHS = {
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/prometheus"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(
            HttpSecurity http,
            BankingSecurityProperties properties,
            ApiSecurityErrorWriter errorWriter,
            ObjectProvider<JwtDecoder> jwtDecoder,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
            String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
            String jwkSetUri) throws Exception {
        requireOneTokenSource(properties, issuerUri, jwkSetUri);

        // Without this check the same mistake surfaces as a bare "no qualifying
        // bean of type JwtDecoder", which says nothing about how to fix it.
        if (jwtDecoder.getIfAvailable() == null) {
            throw new IllegalStateException("""
                    No token decoder is configured, so no request could be \
                    authenticated. Set the spring.security.oauth2.resourceserver.jwt \
                    properties issuer-uri, jwk-set-uri and audiences to trust an \
                    identity provider, or activate the "%s" profile to accept \
                    locally signed development tokens.""".formatted(DEV_PROFILE));
        }

        return http
                // Bearer tokens carry no ambient browser authority, so there is
                // no cross-site request to forge and no session to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(PUBLIC_OPERATIONAL_PATHS).permitAll()
                        .requestMatchers("/actuator/**")
                                .hasAuthority(properties.adminAuthority())
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(
                                jwtAuthenticationConverter(properties)))
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter))
                .build();
    }

    /**
     * Maps the provider's permission claim onto Spring Security authorities,
     * so a token from any provider can express the administrative permission.
     */
    private static JwtAuthenticationConverter jwtAuthenticationConverter(
            BankingSecurityProperties properties) {
        JwtGrantedAuthoritiesConverter authorities =
                new JwtGrantedAuthoritiesConverter();

        authorities.setAuthoritiesClaimName(properties.authoritiesClaim());
        authorities.setAuthorityPrefix(properties.authorityPrefix());

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);

        return converter;
    }

    /**
     * Verifies locally signed tokens so the backend runs without an identity
     * provider. It exists only under the {@value #DEV_PROFILE} profile, which
     * nothing activates by default, so no other configuration can accept a token
     * signed with the shared secret, whatever its properties say.
     */
    @Bean
    @Profile(DEV_PROFILE)
    JwtDecoder devJwtDecoder(BankingSecurityProperties properties) {
        if (!hasText(properties.devJwtSecret())) {
            throw new IllegalStateException("""
                    The "%s" profile is active but banking.security.dev-jwt-secret \
                    is empty. Set it, or leave the profile off and configure an \
                    identity provider.""".formatted(DEV_PROFILE));
        }

        logger.warn("Accepting locally signed tokens from "
                + "banking.security.dev-jwt-secret. Anyone holding this secret can "
                + "mint tokens for any account, including administrative ones. "
                + "Never activate the \"" + DEV_PROFILE + "\" profile in a "
                + "deployed environment.");

        SecretKey key = new SecretKeySpec(
                properties.devJwtSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");

        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * A development secret next to identity provider settings is almost
     * certainly a mistake, whichever profile is active, so it is rejected rather
     * than silently resolved in favour of one of them.
     */
    private static void requireOneTokenSource(
            BankingSecurityProperties properties,
            String issuerUri,
            String jwkSetUri) {
        if (hasText(properties.devJwtSecret())
                && (hasText(issuerUri) || hasText(jwkSetUri))) {
            throw new IllegalStateException("""
                    Both banking.security.dev-jwt-secret and an OAuth2 issuer-uri \
                    or jwk-set-uri are configured. Set the identity provider alone \
                    to trust it, or activate the "%s" profile alone to sign tokens \
                    locally.""".formatted(DEV_PROFILE));
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
