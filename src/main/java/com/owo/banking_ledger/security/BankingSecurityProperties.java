package com.owo.banking_ledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings that adapt the service to whichever identity provider issues its
 * tokens. Providers disagree about where permissions live in a token, so the
 * claim name, the prefix Spring Security adds, and the authority that marks an
 * administrator are all configurable.
 *
 * <p>The claim must be a top-level one: Spring Security looks it up by its
 * literal name and cannot follow a path such as Keycloak's
 * {@code realm_access.roles}. Keycloak is therefore configured with a mapper
 * that copies the caller's roles on the API client into a top-level
 * {@code ledger_roles} claim, and development tokens use the OAuth2
 * {@code scope} claim.
 *
 * @param adminAuthority   authority a token must carry to perform
 *                         administrative operations
 * @param authoritiesClaim top-level token claim listing the caller's permissions
 * @param authorityPrefix  prefix Spring Security prepends to each claim value
 * @param devJwtSecret     HMAC secret the {@code dev} profile signs and verifies
 *                         local tokens with; it has no effect without that
 *                         profile, and is refused next to identity provider
 *                         settings
 */
@ConfigurationProperties("banking.security")
public record BankingSecurityProperties(
        String adminAuthority,
        String authoritiesClaim,
        String authorityPrefix,
        String devJwtSecret) {

    public BankingSecurityProperties {
        adminAuthority = adminAuthority == null
                ? "SCOPE_ledger:admin"
                : adminAuthority;

        authoritiesClaim = authoritiesClaim == null
                ? "scope"
                : authoritiesClaim;

        authorityPrefix = authorityPrefix == null
                ? "SCOPE_"
                : authorityPrefix;
    }
}
