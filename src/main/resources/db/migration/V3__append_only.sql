-- G5, V1 (C4): the ledger and the sku rows are append-only. Statement-level, so an UPDATE, DELETE or TRUNCATE fails
-- even when no row matches. Tests clean up with TRUNCATE after SET LOCAL session_replication_role = replica, a
-- transaction-scoped bypass that production code never sets. The replica role is for test cleanup only: it also
-- skips foreign-key checks. A table owner or superuser can still disable the triggers. A later migration that must
-- change these rows runs, inside its transaction, ALTER TABLE <table> DISABLE TRIGGER <name>, its statements, then
-- ALTER TABLE <table> ENABLE TRIGGER <name>, so foreign keys stay enforced.
-- idempotency_keys has no trigger: R2/Y4 complete a claim with an UPDATE.
CREATE FUNCTION reject_ledger_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the table is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER inventory_ledger_append_only
    BEFORE UPDATE OR DELETE ON inventory_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER inventory_ledger_no_truncate
    BEFORE TRUNCATE ON inventory_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER sku_append_only
    BEFORE UPDATE OR DELETE ON sku
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();

CREATE TRIGGER sku_no_truncate
    BEFORE TRUNCATE ON sku
    FOR EACH STATEMENT EXECUTE FUNCTION reject_ledger_change();
