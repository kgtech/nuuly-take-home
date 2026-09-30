package com.kgtech.inventoryapi.inventory;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * All stock SQL (DESIGN-V2 §2): conditional row updates through JdbcClient. Postgres writes the append-only ledger row
 * whenever a balance changes (V5 trigger, A14), so this class never inserts one.
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

    /** No oversell: the row is updated only when enough stock is on hand. */
    static final String PURCHASE = """
            UPDATE sku SET quantity = quantity - :q, version = version + 1
            WHERE sku_id = :id AND quantity >= :q
            RETURNING quantity, version
            """;

    static final String EXISTS = "SELECT EXISTS (SELECT 1 FROM sku WHERE sku_id = :id)";

    static final String FIND = "SELECT quantity, version FROM sku WHERE sku_id = :id";

    /** G9: keyset page in COLLATE "C" order, straight from the balance column. */
    static final String PAGE = """
            SELECT sku_id, quantity FROM sku WHERE sku_id > :after ORDER BY sku_id LIMIT :limit
            """;

    private final JdbcClient jdbc;

    StockRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inside the caller's transaction: create the SKU if needed, then add. Empty → overflow (nothing written). */
    Optional<Balance> add(String skuId, int quantity) {
        jdbc.sql(INSERT_SKU).param("id", skuId).update();
        return jdbc.sql(ADD).param("id", skuId).param("q", quantity).param("max", MAX_QUANTITY)
                .query(Balance.class).optional();
    }

    /** Inside the caller's transaction: deduct. Empty → missing SKU or insufficient stock. */
    Optional<Balance> purchase(String skuId, int quantity) {
        return jdbc.sql(PURCHASE).param("id", skuId).param("q", quantity).query(Balance.class).optional();
    }

    boolean exists(String skuId) {
        return jdbc.sql(EXISTS).param("id", skuId).query(Boolean.class).single();
    }

    /** Autocommit read at READ COMMITTED: never waits on a writer (DESIGN-V2 §9). */
    Optional<Balance> find(String skuId) {
        return jdbc.sql(FIND).param("id", skuId).query(Balance.class).optional();
    }

    List<InventoryItem> page(String after, long limit) {
        return jdbc.sql(PAGE).param("after", after).param("limit", limit)
                .query((rs, n) -> new InventoryItem(rs.getString("sku_id"), rs.getLong("quantity"))).list();
    }
}
