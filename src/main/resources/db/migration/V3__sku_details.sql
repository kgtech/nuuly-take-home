-- DESIGN-V2 §8: a SKU's details live beside the balance row, never on it; the field rules are CHECKs.
CREATE TABLE sku_details (
    sku_id        varchar(64) COLLATE "C" PRIMARY KEY REFERENCES sku (sku_id),
    name          text NOT NULL CHECK (length(name) BETWEEN 1 AND 120 AND name !~ '^\s*$'),
    description   text NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
    cost_amount   bigint CHECK (cost_amount >= 0),
    cost_currency char(3) CHECK (cost_currency ~ '^[A-Z]{3}$'),
    images        text[] NOT NULL DEFAULT '{}' CHECK (cardinality(images) <= 10),
    version       bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT sku_details_cost_complete CHECK ((cost_amount IS NULL) = (cost_currency IS NULL))
);

CREATE TRIGGER sku_details_never_deleted
    BEFORE DELETE ON sku_details
    FOR EACH ROW EXECUTE FUNCTION forbid_row_change();

-- A keyed create stores 201 or 409 (A28); its operation is 'create'.
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_operation_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_operation_check
    CHECK (operation IN ('add', 'purchase', 'create'));
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_status_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_status_check
    CHECK (status IN (200, 201, 400, 404, 409));
