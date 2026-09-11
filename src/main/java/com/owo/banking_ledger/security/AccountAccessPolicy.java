package com.owo.banking_ledger.security;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.owo.banking_ledger.account.Account;
import com.owo.banking_ledger.account.AccountNotFoundException;
import com.owo.banking_ledger.account.AccountOwnership;
import com.owo.banking_ledger.account.AccountRepository;
import com.owo.banking_ledger.common.BusinessErrorCode;
import com.owo.banking_ledger.common.BusinessException;

/**
 * The one place account authorization is decided.
 *
 * <p>Checks live here and are called from the services rather than from the
 * controllers, so a caller cannot reach an account by arriving through a
 * different entry point than the one a rule was written for.
 *
 * <p>Denials answer {@code 403} rather than {@code 404}. That confirms an
 * account id exists, which is a deliberate trade: it keeps the authorization
 * boundary observable and debuggable. A deployment that treats account ids as
 * secret should map {@link BusinessErrorCode#ACCESS_DENIED} to a not-found
 * response instead.
 */
@Component
public class AccountAccessPolicy {

    private final AccountRepository accountRepository;
    private final AuthenticatedCaller caller;

    public AccountAccessPolicy(
            AccountRepository accountRepository,
            AuthenticatedCaller caller) {
        this.accountRepository = accountRepository;
        this.caller = caller;
    }

    /**
     * Authorizes the caller against an account it named by id, before any work
     * is done on that account. Administrators pass for every account;
     * a customer passes only for accounts it owns.
     */
    @Transactional(readOnly = true)
    public void requireAccountAccess(Long accountId) {
        if (caller.isAdmin()) {
            return;
        }

        // Reads ownership only. Loading the account here would seed the
        // persistence context, and the locking read that follows in the calling
        // service would then be served that stale copy instead of the row it
        // just locked, failing the version check at flush under contention.
        AccountOwnership ownership = accountRepository
                .findOwnershipById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        if (!isOwnedBy(ownership, caller.subject())) {
            throw new BusinessException(
                    BusinessErrorCode.ACCESS_DENIED,
                    "Caller is not authorized for account " + accountId);
        }
    }

    /**
     * Mirrors {@link Account#isOwnedBy}: a system account has no owning
     * subject, so it is never owned by a caller.
     */
    private static boolean isOwnedBy(AccountOwnership ownership, String subject) {
        return ownership.getOwnerSubject() != null
                && ownership.getOwnerSubject().equals(subject);
    }

    /**
     * Authorizes the caller against an account already loaded by the caller's
     * transaction, avoiding a second read of a row it just locked.
     */
    public void requireAccountAccess(Account account) {
        if (caller.isAdmin()) {
            return;
        }

        if (!account.isOwnedBy(caller.subject())) {
            throw new BusinessException(
                    BusinessErrorCode.ACCESS_DENIED,
                    "Caller is not authorized for account " + account.getId());
        }
    }

    /**
     * Gates operations that act on the bank's behalf rather than an account
     * holder's, such as freezing an account or reversing a posted transaction.
     */
    public void requireAdmin(String operation) {
        if (!caller.isAdmin()) {
            throw new BusinessException(
                    BusinessErrorCode.ACCESS_DENIED,
                    "Administrative permission is required to " + operation);
        }
    }

    public String currentSubject() {
        return caller.subject();
    }
}
