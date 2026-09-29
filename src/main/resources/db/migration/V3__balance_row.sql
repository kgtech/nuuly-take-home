-- V3 (E1, E3): the balance is a row. quantity and version are added, backfilled from the ledger,
-- then inventory_ledger becomes append-only and sku rows undeletable, enforced by triggers. TRUNCATE is not blocked.
ALTER TABLE sku
    ADD COLUMN quantity bigint NOT NULL DEFAULT 0 CONSTRAINT sku_quantity_check CHECK (quantity >= 0),
    ADD COLUMN version  bigint NOT NULL DEFAULT 0;

UPDATE sku s
SET quantity = l.total,
    version  = l.changes
FROM (SELECT sku_id, SUM(quantity_delta)::bigint AS total, count(*) AS changes
      FROM inventory_ledger
      GROUP BY sku_id) l
WHERE l.sku_id = s.sku_id;

CREATE FUNCTION forbid_row_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% on % is not allowed', TG_OP, TG_TABLE_NAME USING ERRCODE = 'P0001';
END
$$;

CREATE TRIGGER inventory_ledger_append_only
    BEFORE UPDATE OR DELETE ON inventory_ledger
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();

CREATE TRIGGER sku_never_deleted
    BEFORE DELETE ON sku
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();
