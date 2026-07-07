ALTER TABLE accounts
    ADD COLUMN account_kind VARCHAR(20) NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN account_category VARCHAR(20) NOT NULL DEFAULT 'LIABILITY';

ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_kind
        CHECK (account_kind IN ('CUSTOMER', 'SYSTEM')),
    ADD CONSTRAINT chk_accounts_category
        CHECK (account_category IN ('ASSET', 'LIABILITY'));

CREATE TABLE ledger_transactions (
    id BIGSERIAL PRIMARY KEY,
    reference_id VARCHAR(64) NOT NULL UNIQUE,
    transaction_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_transaction_type
        CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),

    CONSTRAINT chk_transaction_status
        CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),

    CONSTRAINT chk_transaction_amount
        CHECK (amount > 0)
);

CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19, 4) NOT NULL,
    balance_after NUMERIC(19, 4) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_entry_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES ledger_transactions(id),

    CONSTRAINT fk_entry_account
        FOREIGN KEY (account_id)
        REFERENCES accounts(id),

    CONSTRAINT chk_entry_type
        CHECK (entry_type IN ('DEBIT', 'CREDIT')),

    CONSTRAINT chk_entry_amount
        CHECK (amount > 0)
);

CREATE INDEX idx_ledger_entries_transaction_id
    ON ledger_entries(transaction_id);

CREATE INDEX idx_ledger_entries_account_id
    ON ledger_entries(account_id);

INSERT INTO accounts (
    account_number,
    owner_name,
    currency,
    status,
    balance,
    version,
    created_at,
    account_kind,
    account_category
)
VALUES (
    'SYSTEM-CASH-AUD',
    'Bank System',
    'AUD',
    'ACTIVE',
    0,
    0,
    CURRENT_TIMESTAMP,
    'SYSTEM',
    'ASSET'
)
ON CONFLICT (account_number) DO NOTHING;
