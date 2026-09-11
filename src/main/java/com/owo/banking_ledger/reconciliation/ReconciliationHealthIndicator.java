package com.owo.banking_ledger.reconciliation;

import java.util.Optional;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces reconciliation state through {@code /actuator/health}, so a
 * deployment that already watches health gets the signal without having to
 * scrape metrics or configure alert rules.
 */
@Component("reconciliation")
public class ReconciliationHealthIndicator implements HealthIndicator {

    private final ReconciliationService reconciliationService;

    public ReconciliationHealthIndicator(
            ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @Override
    public Health health() {
        Optional<ReconciliationRun> latest = reconciliationService.findLatestRun();

        if (latest.isEmpty()) {
            // Nothing has been checked yet, which is normal right after start
            // and is not the same as having found a problem.
            return Health.unknown()
                    .withDetail("reason", "no reconciliation run has completed yet")
                    .build();
        }

        ReconciliationRun run = latest.get();
        Health.Builder builder = run.isClean()
                ? Health.up()
                : Health.down();

        return builder
                .withDetail("lastRunId", run.getId())
                .withDetail("lastRunAt", run.getStartedAt())
                .withDetail("accountsChecked", run.getAccountsChecked())
                .withDetail("differenceCount", run.getDifferenceCount())
                .build();
    }
}
