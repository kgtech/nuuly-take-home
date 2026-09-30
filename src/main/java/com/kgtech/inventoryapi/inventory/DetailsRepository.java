package com.kgtech.inventoryapi.inventory;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/** All SQL of the v2 details (DESIGN-V2 §8), through JdbcClient. Writes run inside the caller's transaction. */
@Repository
class DetailsRepository {

    /** PUT details: a row back means this transaction created the SKU; none means it exists. */
    static final String CLAIM_SKU = "INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING RETURNING sku_id";

    /** Images travel as one newline-separated string (a URL cannot contain a newline) and become text[]. */
    private static final String IMAGES = "CASE WHEN :images = '' THEN '{}'::text[] ELSE string_to_array(:images, E'\\n') END";

    static final String INSERT = """
            INSERT INTO sku_details (sku_id, name, description, cost_amount, cost_currency, images)
            VALUES (:id, :name, :description, :amount, :currency, %s)
            RETURNING version
            """.formatted(IMAGES);

    /** §8 "Edit", unconditional: upsert gated on the SKU's existence; this statement never creates a SKU. */
    static final String REPLACE_ANY = """
            WITH target AS (SELECT s.sku_id FROM sku s WHERE s.sku_id = :id)
            INSERT INTO sku_details (sku_id, name, description, cost_amount, cost_currency, images)
            SELECT sku_id, CAST(:name AS text), CAST(:description AS text), CAST(:amount AS bigint),
                   CAST(:currency AS char(3)), %s FROM target
            ON CONFLICT (sku_id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                cost_amount = EXCLUDED.cost_amount, cost_currency = EXCLUDED.cost_currency, images = EXCLUDED.images,
                version = sku_details.version + 1, updated_at = now()
            RETURNING version
            """.formatted(IMAGES);

    /**
     * §8 "Edit", conditional: the current version (0 without details) must be listed both before the insert and,
     * re-checked against the locked row, in the update, so a concurrent PUT cannot slip between the two.
     */
    static final String REPLACE_IF = """
            WITH target AS (SELECT s.sku_id FROM sku s LEFT JOIN sku_details d ON d.sku_id = s.sku_id
                            WHERE s.sku_id = :id AND COALESCE(d.version, 0) IN (:versions))
            INSERT INTO sku_details (sku_id, name, description, cost_amount, cost_currency, images)
            SELECT sku_id, CAST(:name AS text), CAST(:description AS text), CAST(:amount AS bigint),
                   CAST(:currency AS char(3)), %s FROM target
            ON CONFLICT (sku_id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
                cost_amount = EXCLUDED.cost_amount, cost_currency = EXCLUDED.cost_currency, images = EXCLUDED.images,
                version = sku_details.version + 1, updated_at = now()
            WHERE sku_details.version IN (:versions)
            RETURNING version
            """.formatted(IMAGES);

    private static final String ITEM = """
            SELECT s.sku_id, s.quantity, d.name, d.description, d.cost_amount, d.cost_currency, d.images,
                   COALESCE(d.version, 0) AS details_version
            FROM sku s LEFT JOIN sku_details d ON d.sku_id = s.sku_id
            """;

    /** §8 "Reads": one primary-key join, autocommit at READ COMMITTED. */
    static final String FIND = ITEM + "WHERE s.sku_id = :id";

    /** G9 keyset page in COLLATE "C" order, with the details joined. */
    static final String PAGE = ITEM + "WHERE s.sku_id > :after ORDER BY s.sku_id LIMIT :limit";

    private final JdbcClient jdbc;

    DetailsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inside the caller's transaction: true when this transaction created the SKU row. */
    boolean claimSku(String skuId) {
        return jdbc.sql(CLAIM_SKU).param("id", skuId).query((rs, n) -> rs.getString(1)).optional().isPresent();
    }

    /** Inside the caller's transaction, after {@link #claimSku}: the details row; returns its version (1). */
    long insert(String skuId, SkuDetails details) {
        return withDetails(jdbc.sql(INSERT).param("id", skuId), details).query(Long.class).single();
    }

    /** Inside the caller's transaction: the new version, or empty when the SKU is missing or the version differs. */
    Optional<Long> replace(String skuId, SkuDetails details, DetailsPrecondition precondition) {
        return switch (precondition) {
            case DetailsPrecondition.Any _, DetailsPrecondition.Exists _ ->
                    withDetails(jdbc.sql(REPLACE_ANY).param("id", skuId), details).query(Long.class).optional();
            case DetailsPrecondition.Absent _ -> Optional.empty(); // this only replaces: an existing SKU fails it
            case DetailsPrecondition.Versions v when v.versions().isEmpty() -> Optional.empty();
            case DetailsPrecondition.Versions v -> withDetails(jdbc.sql(REPLACE_IF).param("id", skuId)
                    .param("versions", v.versions()), details).query(Long.class).optional();
        };
    }

    Optional<SkuItem> find(String skuId) {
        return jdbc.sql(FIND).param("id", skuId).query(DetailsRepository::item).optional();
    }

    List<SkuItem> page(String after, long limit) {
        return jdbc.sql(PAGE).param("after", after).param("limit", limit).query(DetailsRepository::item).list();
    }

    private static StatementSpec withDetails(StatementSpec spec, SkuDetails details) {
        return spec.param("name", details.name())
                .param("description", details.description())
                .param("amount", details.cost().map(SkuCost::amount).orElse(null), Types.BIGINT)
                .param("currency", details.cost().map(SkuCost::currency).orElse(null), Types.CHAR)
                .param("images", String.join("\n", details.images()));
    }

    private static SkuItem item(ResultSet rs, int rowNum) throws SQLException {
        String name = rs.getString("name");
        Optional<SkuDetails> details = Optional.empty();
        if (name != null) {
            long amount = rs.getLong("cost_amount");
            Optional<SkuCost> cost = rs.wasNull() ? Optional.empty()
                    : Optional.of(new SkuCost(amount, rs.getString("cost_currency")));
            Array images = rs.getArray("images");
            details = Optional.of(new SkuDetails(name, rs.getString("description"), cost,
                    List.of((String[]) images.getArray())));
        }
        return new SkuItem(rs.getString("sku_id"), rs.getLong("quantity"), details, rs.getLong("details_version"));
    }
}
