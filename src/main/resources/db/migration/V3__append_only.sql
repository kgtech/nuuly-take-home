-- G5, V1 (C4): the ledger and the sku rows are append-only. Statement-level, so an UPDATE or DELETE fails even when
-- no row matches. TRUNCATE fires no UPDATE/DELETE trigger; only test cleanup uses it. idempotency_keys has no
-- trigger: R2/Y4 complete a claim with an UPDATE.
CREATE FUNCTION reject_update_or_delete() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: the table is append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END
$$;

CREATE TRIGGER inventory_ledger_append_only
    BEFORE UPDATE OR DELETE ON inventory_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION reject_update_or_delete();

CREATE TRIGGER sku_append_only
    BEFORE UPDATE OR DELETE ON sku
    FOR EACH STATEMENT EXECUTE FUNCTION reject_update_or_delete();
