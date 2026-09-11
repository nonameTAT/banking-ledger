package com.owo.banking_ledger.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

/**
 * Reads the caller's identity out of the security context.
 *
 * <p>Identity is taken from {@link Authentication#getName()} rather than from
 * the {@code Jwt} directly. For a bearer token that resolves to the subject
 * claim, so the service stays independent of how the token was built and the
 * same code path works under any authentication mechanism.
 */
@Component
public class AuthenticatedCaller {

    private final BankingSecurityProperties properties;

    public AuthenticatedCaller(BankingSecurityProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the subject claim identifying the caller
     * @throws BusinessException if the request reached this point unauthenticated
     */
    public String subject() {
        Authentication authentication = authentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(
                    BusinessErrorCode.UNAUTHENTICATED,
                    "Request is not authenticated");
        }

        String subject = authentication.getName();

        if (subject == null || subject.isBlank()) {
            throw new BusinessException(
                    BusinessErrorCode.UNAUTHENTICATED,
                    "Authenticated token carries no subject");
        }

        return subject;
    }

    public boolean isAdmin() {
        Authentication authentication = authentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        return authentication.getAuthorities()
                .stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(properties.adminAuthority()::equals);
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
