package com.owo.banking_ledger.reconciliation;

import java.util.List;

/**
 * What one committed reconciliation pass produced.
 *
 * <p>Carries the differing account ids alongside the stored run so the caller
 * can report them without reading them back, while still only reporting what
 * actually reached the database.
 */
record ReconciliationOutcome(
        ReconciliationRun run,
        List<Long> differingAccountIds) {
}
