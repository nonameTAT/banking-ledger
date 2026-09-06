ALTER TABLE ledger_transactions
    ADD COLUMN reversal_of_id BIGINT;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_ledger_transactions_reversal_of
        FOREIGN KEY (reversal_of_id)
        REFERENCES ledger_transactions(id),

    ADD CONSTRAINT chk_transaction_reversal_not_self
        CHECK (reversal_of_id IS NULL OR reversal_of_id <> id);

-- A transaction may be reversed at most once.
CREATE UNIQUE INDEX uq_ledger_transactions_reversal_of
    ON ledger_transactions(reversal_of_id)
    WHERE reversal_of_id IS NOT NULL;

ALTER TABLE ledger_transactions
    DROP CONSTRAINT chk_transaction_type,
    ADD CONSTRAINT chk_transaction_type
        CHECK (transaction_type IN (
            'DEPOSIT',
            'WITHDRAWAL',
            'TRANSFER',
            'REVERSAL'
        ));

ALTER TABLE ledger_transactions
    DROP CONSTRAINT chk_transaction_status,
    ADD CONSTRAINT chk_transaction_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED'));

ALTER TABLE audit_logs
    DROP CONSTRAINT chk_audit_logs_action,
    ADD CONSTRAINT chk_audit_logs_action
        CHECK (action IN (
            'ACCOUNT_CREATED',
            'ACCOUNT_FROZEN',
            'ACCOUNT_UNFROZEN',
            'DEPOSIT_COMPLETED',
            'WITHDRAWAL_COMPLETED',
            'TRANSFER_COMPLETED',
            'TRANSACTION_REVERSED'
        ));
