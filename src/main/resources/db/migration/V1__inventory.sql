CREATE TABLE sku (
    sku_id     varchar(64) COLLATE "C" PRIMARY KEY,
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
