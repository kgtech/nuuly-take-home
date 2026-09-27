-- DESIGN-V2 §1: the balance is a row, guarded by CHECK and conditional UPDATEs; the ledger records every change.
CREATE TABLE sku (
    sku_id     varchar(64) COLLATE "C" PRIMARY KEY,
    quantity   bigint NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    version    bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inventory_ledger (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    sku_id         varchar(64) COLLATE "C" NOT NULL REFERENCES sku (sku_id),
    quantity_delta bigint NOT NULL CHECK (quantity_delta <> 0),
    reason         text NOT NULL CHECK (reason IN ('add', 'purchase')),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX inventory_ledger_sku ON inventory_ledger (sku_id);

-- Append-only, enforced in Postgres (C-10): rows are never updated or deleted; TRUNCATE (tests) is not blocked.
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
