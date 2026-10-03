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
     * Decides whose accounts a listing may contain, before it runs.
     * Administrators may list every account or narrow to any one owner. A
     * customer is held to their own accounts: naming no owner means
     * themselves, and naming anyone else is refused rather than quietly
     * narrowed, so a client asking for the wrong thing is told so instead of
     * being handed a page that looks like an answer.
     *
     * @param requestedOwnerSubject the owner the caller asked to filter by, or
     *        {@code null} for none
     * @return the owner subject the listing must be restricted to, or
     *         {@code null} for no restriction, which only an administrator gets
     */
    public String authorizeAccountListing(String requestedOwnerSubject) {
        if (caller.isAdmin()) {
            return requestedOwnerSubject;
        }

        String subject = caller.subject();

        if (requestedOwnerSubject != null
                && !requestedOwnerSubject.equals(subject)) {
            throw new BusinessException(
                    BusinessErrorCode.ACCESS_DENIED,
                    "Caller is not authorized to list accounts of another owner");
        }

        return subject;
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
