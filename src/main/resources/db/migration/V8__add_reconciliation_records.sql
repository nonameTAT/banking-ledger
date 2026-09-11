-- Reconciliation compares each account's materialized balance against the
-- balance derived from its ledger entries. Results are stored rather than only
-- counted, because a metric can say that something drifted but not which
-- account, by how much, or when it started.

CREATE TABLE reconciliation_runs (
    id BIGSERIAL PRIMARY KEY,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accounts_checked INTEGER NOT NULL,
    difference_count INTEGER NOT NULL,

    -- Ties a stored run back to the log lines it produced.
    trace_id VARCHAR(64),

    CONSTRAINT chk_reconciliation_runs_counts
        CHECK (
            accounts_checked >= 0
            AND difference_count >= 0
            AND difference_count <= accounts_checked
        )
);

-- Queries are almost always "what happened recently".
CREATE INDEX idx_reconciliation_runs_started_at
    ON reconciliation_runs(started_at DESC);

CREATE TABLE reconciliation_differences (
    id BIGSERIAL PRIMARY KEY,
    run_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    recorded_balance NUMERIC(19, 4) NOT NULL,
    derived_balance NUMERIC(19, 4) NOT NULL,
    difference NUMERIC(19, 4) NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_reconciliation_differences_run
        FOREIGN KEY (run_id)
        REFERENCES reconciliation_runs(id),

    CONSTRAINT fk_reconciliation_differences_account
        FOREIGN KEY (account_id)
        REFERENCES accounts(id),

    -- A difference row exists only when the two balances actually disagree.
    CONSTRAINT chk_reconciliation_differences_nonzero
        CHECK (difference <> 0)
);

CREATE INDEX idx_reconciliation_differences_run
    ON reconciliation_differences(run_id);

CREATE INDEX idx_reconciliation_differences_account
    ON reconciliation_differences(account_id, detected_at DESC);
