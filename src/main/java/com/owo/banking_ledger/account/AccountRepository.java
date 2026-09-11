package com.owo.banking_ledger.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByAccountNumber(String accountNumber);

    boolean existsByAccountNumberAndAccountKind(
            String accountNumber,
            AccountKind accountKind);

    Optional<Account> findByAccountNumber(String accountNumber);

    /**
     * Reads ownership without loading the account, so an authorization check
     * cannot leave a stale copy in the persistence context for a later locking
     * read to pick up. See {@link AccountOwnership}.
     */
    @Query("SELECT a.id AS id, a.ownerSubject AS ownerSubject FROM Account a WHERE a.id = :id")
    Optional<AccountOwnership> findOwnershipById(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Account a WHERE a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT a
            FROM Account a
            WHERE a.accountNumber = :accountNumber
            """)
    Optional<Account> findByAccountNumberForUpdate(
            @Param("accountNumber") String accountNumber);
}
