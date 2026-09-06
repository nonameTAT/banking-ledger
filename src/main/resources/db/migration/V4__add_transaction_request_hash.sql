ALTER TABLE ledger_transactions
    ADD COLUMN request_hash VARCHAR(64);

COMMENT ON COLUMN ledger_transactions.request_hash IS
    'SHA-256 fingerprint of the request that created the transaction. '
    'Used to replay an identical retry and to reject a reused reference id '
    'that carries a different payload. Null for rows created before '
    'idempotent replay was introduced.';
