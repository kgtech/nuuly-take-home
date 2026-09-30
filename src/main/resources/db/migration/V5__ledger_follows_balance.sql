-- A14: Postgres writes the ledger row whenever sku.quantity changes, so the balance and the ledger sum can't drift
-- apart. The conditional UPDATE still decides and returns the balance (E1); the app no longer inserts ledger rows.
-- Limit: the table owner or a superuser can still bypass this (DISABLE TRIGGER, TRUNCATE inventory_ledger,
-- session_replication_role = replica, or a trigger of its own that inserts ledger rows), and the app connects as the
-- owner (E3).

-- Start from a consistent database: never carry existing drift forward.
DO $$
BEGIN
    IF EXISTS (SELECT 1
               FROM sku s
               LEFT JOIN (SELECT sku_id, SUM(quantity_delta) AS total FROM inventory_ledger GROUP BY sku_id) l
                   USING (sku_id)
               WHERE s.quantity <> COALESCE(l.total, 0)) THEN
        RAISE EXCEPTION 'sku.quantity differs from the ledger sum for at least one SKU; reconcile before V5';
    END IF;
END
$$;

-- search_path is pinned so a session's temporary table named inventory_ledger can't capture the row.
CREATE FUNCTION record_balance_change() RETURNS trigger
    LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
DECLARE
    delta bigint := NEW.quantity - CASE WHEN TG_OP = 'INSERT' THEN 0 ELSE OLD.quantity END;
BEGIN
    INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
    VALUES (NEW.sku_id, delta, CASE WHEN delta > 0 THEN 'add' ELSE 'purchase' END);
    RETURN NULL;
END
$$;

-- No column list (not UPDATE OF quantity): a change that another BEFORE trigger makes to quantity is recorded too.
CREATE TRIGGER sku_balance_recorded_on_update
    AFTER UPDATE ON sku
    FOR EACH ROW WHEN (NEW.quantity IS DISTINCT FROM OLD.quantity)
    EXECUTE FUNCTION record_balance_change();

CREATE TRIGGER sku_balance_recorded_on_insert
    AFTER INSERT ON sku
    FOR EACH ROW WHEN (NEW.quantity <> 0)
    EXECUTE FUNCTION record_balance_change();

-- No ledger row without a balance change: only record_balance_change() (trigger depth 2) may insert.
CREATE FUNCTION ledger_only_from_balance() RETURNS trigger
    LANGUAGE plpgsql SET search_path = public, pg_temp AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'inventory_ledger is written only by sku balance changes' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER inventory_ledger_only_from_balance
    BEFORE INSERT ON inventory_ledger
    FOR EACH ROW EXECUTE FUNCTION ledger_only_from_balance();
