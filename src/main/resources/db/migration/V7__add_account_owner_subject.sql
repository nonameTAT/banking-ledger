-- Links a customer account to the identity that owns it. The value is the
-- subject claim issued by the identity provider, which is why the column is a
-- plain string rather than a foreign key: identities live outside this service.
ALTER TABLE accounts
    ADD COLUMN owner_subject VARCHAR(255);

-- Ownership is checked on nearly every request, so the lookup gets an index.
CREATE INDEX idx_accounts_owner_subject
    ON accounts(owner_subject);

-- Accounts that predate authentication have no identity to attribute them to.
-- Giving each one a synthetic subject no provider can issue keeps them readable
-- by administrators while making sure no customer inherits someone else's
-- account.
UPDATE accounts
    SET owner_subject = 'legacy:unclaimed:' || id
    WHERE account_kind = 'CUSTOMER'
        AND owner_subject IS NULL;

-- System accounts are internal and belong to no external identity, so the
-- column stays NULL for them and is required for everyone else.
ALTER TABLE accounts
    ADD CONSTRAINT chk_accounts_owner_subject
        CHECK (
            account_kind <> 'CUSTOMER'
            OR owner_subject IS NOT NULL
        );
