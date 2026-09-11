package com.owo.banking_ledger.reconciliation;

import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface ReconciliationRepository extends Repository<ReconciliationRun, Long> {

    /**
     * Derives every account's balance from its ledger entries and returns it
     * next to the stored balance.
     *
     * <p>The sign of an entry depends on what kind of account it lands on: a
     * debit raises an asset and lowers a liability, and a credit does the
     * reverse. That is the same rule {@code Account.debit} and
     * {@code Account.credit} apply when posting, expressed here over the whole
     * table so the two can be compared.
     *
     * <p>Accounts with no entries are kept by the outer join, since an account
     * holding a balance it has no entries for is exactly the kind of drift
     * worth catching.
     */
    @Query(value = """
            SELECT a.id AS accountId,
                   a.balance AS recordedBalance,
                   COALESCE(SUM(
                       CASE
                           WHEN e.entry_type = 'DEBIT' AND a.account_category = 'ASSET'
                               THEN e.amount
                           WHEN e.entry_type = 'CREDIT' AND a.account_category = 'ASSET'
                               THEN -e.amount
                           WHEN e.entry_type = 'CREDIT' AND a.account_category = 'LIABILITY'
                               THEN e.amount
                           WHEN e.entry_type = 'DEBIT' AND a.account_category = 'LIABILITY'
                               THEN -e.amount
                       END
                   ), 0) AS derivedBalance
            FROM accounts a
            LEFT JOIN ledger_entries e ON e.account_id = a.id
            GROUP BY a.id, a.balance
            ORDER BY a.id
            """, nativeQuery = true)
    List<AccountBalanceComparison> compareBalances();
}
