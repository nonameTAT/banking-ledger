package com.owo.banking_ledger.account;

/**
 * Naming convention for the seeded system accounts.
 */
public final class SystemAccounts {

    private static final String CASH_ACCOUNT_PREFIX = "SYSTEM-CASH-";

    private SystemAccounts() {
    }

    public static String cashAccountNumber(String currency) {
        return CASH_ACCOUNT_PREFIX + currency;
    }
}
