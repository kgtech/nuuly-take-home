package com.kgtech.inventoryapi.inventory;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;

/** JdbcClient implementation of the ledger writes (V1). Append-only: no UPDATE or DELETE (G5). */
class InventoryWritesImpl implements InventoryWrites {

    private static final String INSERT_SKU = "INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING";

    private static final String ADD = """
            INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
            SELECT :id, :q, 'add'
            WHERE (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id)
                  <= 9223372036854775807 - :q
            RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) + :q)::bigint
            """;

    private static final String PURCHASE = """
            INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
            SELECT :id, -:q, 'purchase'
            WHERE EXISTS (SELECT 1 FROM sku WHERE sku_id = :id)
              AND (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) >= :q
            RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) - :q)::bigint
            """;

    private static final String SKU_EXISTS = "SELECT EXISTS (SELECT 1 FROM sku WHERE sku_id = :id)";

    private final JdbcClient jdbc;

    InventoryWritesImpl(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StockOutcome.Add add(String skuId, int quantity) {
        jdbc.sql(INSERT_SKU).param("id", skuId).update();
        Optional<Long> balance = jdbc.sql(ADD).param("id", skuId).param("q", quantity).query(Long.class).optional();
        return balance.<StockOutcome.Add>map(StockOutcome.Ok::new).orElseGet(StockOutcome.Overflow::new);
    }

    @Override
    public StockOutcome.Purchase purchase(String skuId, int quantity) {
        Optional<Long> balance =
                jdbc.sql(PURCHASE).param("id", skuId).param("q", quantity).query(Long.class).optional();
        if (balance.isPresent()) {
            return new StockOutcome.Ok(balance.get());
        }
        boolean exists = jdbc.sql(SKU_EXISTS).param("id", skuId).query(Boolean.class).single();
        return exists ? new StockOutcome.Insufficient() : new StockOutcome.NotFound();
    }
}
