package com.owo.banking_ledger.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * <p>Tokens are verified, never issued, here: identities come from an external
 * provider configured through
 * {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}, and Spring Boot
 * builds the decoder that fetches and caches that provider's signing keys.
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
            ApiSecurityErrorWriter errorWriter) throws Exception {
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
     * Verifies locally signed tokens so the stack runs end to end without an
     * identity provider. Configuring an issuer alongside the secret is almost
     * certainly a mistake, so it is rejected rather than silently resolved.
     */
    @Bean
    @ConditionalOnProperty(prefix = "banking.security", name = "dev-jwt-secret")
    JwtDecoder devJwtDecoder(
            BankingSecurityProperties properties,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
            String issuerUri) {
        if (!issuerUri.isBlank()) {
            throw new IllegalStateException("""
                    Both banking.security.dev-jwt-secret and an OAuth2 issuer-uri \
                    are configured. Set the issuer-uri alone to trust an identity \
                    provider, or the dev secret alone to sign tokens locally.""");
        }

        logger.warn("Accepting locally signed tokens from "
                + "banking.security.dev-jwt-secret. Anyone holding this secret can "
                + "mint tokens for any account, including administrative ones. "
                + "Configure an identity provider issuer-uri instead of deploying "
                + "with this enabled.");

        SecretKey key = new SecretKeySpec(
                properties.devJwtSecret().getBytes(StandardCharsets.UTF_8),
                "HmacSHA256");

        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
