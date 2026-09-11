package com.owo.banking_ledger.reconciliation;

import java.math.BigDecimal;

/**
 * One account's stored balance beside the balance its entries add up to.
 * Projected straight out of the aggregate query rather than mapped to entities,
 * so a run costs one statement regardless of how many entries exist.
 */
public interface AccountBalanceComparison {

    Long getAccountId();

    BigDecimal getRecordedBalance();

    BigDecimal getDerivedBalance();
}
