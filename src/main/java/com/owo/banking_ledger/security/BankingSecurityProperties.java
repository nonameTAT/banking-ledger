package com.owo.banking_ledger.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings that adapt the service to whichever identity provider issues its
 * tokens. Providers disagree about where permissions live in a token: the
 * OAuth2 default is a space-delimited {@code scope} claim, Keycloak uses
 * {@code realm_access.roles}, and Auth0 uses {@code permissions}. Rather than
 * hard-coding one shape, the claim name, the prefix Spring Security adds, and
 * the authority that marks an administrator are all configurable.
 *
 * @param adminAuthority   authority a token must carry to perform
 *                         administrative operations
 * @param authoritiesClaim token claim listing the caller's permissions
 * @param authorityPrefix  prefix Spring Security prepends to each claim value
 * @param devJwtSecret     HMAC secret enabling local token signing when no
 *                         external provider is configured; never set this in a
 *                         deployed environment
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
