CREATE TABLE idempotency_keys (
    idempotency_key uuid PRIMARY KEY,
    operation       text NOT NULL CHECK (operation IN ('add', 'purchase')),
    sku_id          varchar(64) COLLATE "C" NOT NULL,
    request_hash    bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    status          smallint CHECK (status IN (200, 400, 404)),
    content_type    text,
    body            text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT idempotency_response_complete CHECK (
        (status IS NULL AND content_type IS NULL AND body IS NULL)
        OR (status IS NOT NULL AND content_type IS NOT NULL AND body IS NOT NULL))
);
