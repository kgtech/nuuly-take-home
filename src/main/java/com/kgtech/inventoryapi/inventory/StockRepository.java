package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All stock SQL (E1, E2): conditional updates of the balance row plus the append-only ledger, through JdbcClient.
 * Writes run inside the caller's READ COMMITTED transaction; find and page are single autocommit queries.
 */
@Repository
class StockRepository {

    static final long MAX_QUANTITY = Long.MAX_VALUE;

    static final String INSERT_SKU = "INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING";

    /** G12: the row is updated only when the new total fits in a bigint. */
    static final String ADD = """
            UPDATE sku SET quantity = quantity + :q, version = version + 1
            WHERE sku_id = :id AND quantity <= :max - :q
            RETURNING quantity, version
            """;

    /**
     * G7: the row is updated only when enough stock is on hand. One statement decides Ok, Insufficient or NotFound
     * from its own snapshot, so a create of the same SKU that hasn't committed when the purchase starts is NotFound,
     * and a purchase that waited on the row lock and then found too little stock is Insufficient (E1).
     */
    static final String PURCHASE = """
            WITH updated AS (
                UPDATE sku SET quantity = quantity - :q, version = version + 1
                WHERE sku_id = :id AND quantity >= :q
                RETURNING quantity, version)
            SELECT (SELECT quantity FROM updated) AS quantity, (SELECT version FROM updated) AS version,
                   EXISTS (SELECT 1 FROM sku WHERE sku_id = :id) AS found
            """;

    static final String LEDGER = "INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (:id, :d, :r)";

    static final String FIND = "SELECT quantity, version FROM sku WHERE sku_id = :id";

    /** G9: keyset page in COLLATE "C" order, straight from the balance column. */
    static final String PAGE = """
            SELECT sku_id, quantity FROM sku WHERE sku_id > :after ORDER BY sku_id LIMIT :limit
            """;

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inside the caller's transaction: create the SKU if needed, add, record. Empty → overflow (nothing written). */
    Optional<Balance> add(String skuId, int quantity) {
        jdbc.sql(INSERT_SKU).param("id", skuId).update();
        Optional<Balance> balance = jdbc.sql(ADD).param("id", skuId).param("q", quantity).param("max", MAX_QUANTITY)
                .query(Balance.class).optional();
        balance.ifPresent(b -> record(skuId, quantity, "add"));
        return balance;
    }

    /** Inside the caller's transaction: deduct and record. Only Ok writes a ledger row. */
    StockOutcome.Purchase purchase(String skuId, int quantity) {
        StockOutcome.Purchase outcome = jdbc.sql(PURCHASE).param("id", skuId).param("q", quantity)
                .<StockOutcome.Purchase>query((rs, n) -> {
                    long remaining = rs.getLong("quantity");
                    if (!rs.wasNull()) {
                        return new StockOutcome.Ok(remaining);
                    }
                    return rs.getBoolean("found") ? new StockOutcome.Insufficient() : new StockOutcome.NotFound();
                })
                .single();
        if (outcome instanceof StockOutcome.Ok) {
            record(skuId, -(long) quantity, "purchase");
        }
        return outcome;
    }

    /** Autocommit read at READ COMMITTED: never waits on a writer (E1). */
    Optional<Balance> find(String skuId) {
        return jdbc.sql(FIND).param("id", skuId).query(Balance.class).optional();
    }

    List<InventoryItem> page(String after, long limit) {
        return jdbc.sql(PAGE).param("after", after).param("limit", limit)
                .query((rs, n) -> new InventoryItem(rs.getString("sku_id"), rs.getLong("quantity"))).list();
    }

    private void record(String skuId, long delta, String reason) {
        jdbc.sql(LEDGER).param("id", skuId).param("d", delta).param("r", reason).update();
    }
}
