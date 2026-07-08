CREATE TABLE audit_logs (
    id BIGSERIAL PRIMARY KEY,
    action VARCHAR(40) NOT NULL,
    account_id BIGINT,
    related_account_id BIGINT,
    transaction_id BIGINT,
    reference_id VARCHAR(64),
    amount NUMERIC(19, 4),
    currency VARCHAR(3),
    details VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_audit_logs_account
        FOREIGN KEY (account_id)
        REFERENCES accounts(id),

    CONSTRAINT fk_audit_logs_related_account
        FOREIGN KEY (related_account_id)
        REFERENCES accounts(id),

    CONSTRAINT fk_audit_logs_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES ledger_transactions(id),

    CONSTRAINT chk_audit_logs_action
        CHECK (action IN (
            'ACCOUNT_CREATED',
            'ACCOUNT_FROZEN',
            'ACCOUNT_UNFROZEN',
            'DEPOSIT_COMPLETED',
            'WITHDRAWAL_COMPLETED',
            'TRANSFER_COMPLETED'
        )),

    CONSTRAINT chk_audit_logs_amount
        CHECK (amount IS NULL OR amount > 0)
);

CREATE INDEX idx_audit_logs_account_id_created_at
    ON audit_logs(account_id, created_at DESC);

CREATE INDEX idx_audit_logs_related_account_id_created_at
    ON audit_logs(related_account_id, created_at DESC);

CREATE INDEX idx_audit_logs_transaction_id
    ON audit_logs(transaction_id);
