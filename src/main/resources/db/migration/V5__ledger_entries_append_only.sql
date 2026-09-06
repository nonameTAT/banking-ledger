-- Posted ledger entries are the permanent accounting record. Corrections are
-- made by posting a reversal transaction, never by changing history, so the
-- database refuses updates and deletes outright.

CREATE FUNCTION reject_ledger_entry_change()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'ledger_entries is append-only: % is not permitted on entry %',
        TG_OP, OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_entry_change();
