-- H9: V3 widened these CHECKs for build v2's keyed create (operation 'create', statuses 201 and 409). OD-6 removed that
-- route, so the table allows again only what the API can store. NOT VALID enforces the CHECKs for new rows without
-- scanning old ones, so a database that still holds a 'create', 201 or 409 row migrates and keeps reading it (keys are
-- never purged, R9); such a key fails as a different operation (400).
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_operation_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_operation_check
    CHECK (operation IN ('add', 'purchase')) NOT VALID;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_status_check;
ALTER TABLE idempotency_keys ADD CONSTRAINT idempotency_keys_status_check
    CHECK (status IN (200, 400, 404)) NOT VALID;
