package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * AC5 / W2: the balance SUM and both write statements read inventory_ledger through inventory_ledger_sku.
 * {@code @Transactional} is allowed here only because this class never calls the service: seed rows, ANALYZE and
 * EXPLAIN all run in the test's own transaction, which rolls back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerQueryPlanTest {

    private static final int SKUS = 500;
    private static final int ROWS_PER_SKU = 10;
    private static final Pattern ID_PARAM = Pattern.compile("(?<!:):id\\b");
    private static final Pattern Q_PARAM = Pattern.compile("(?<!:):q\\b");
    /** A :name placeholder, but not the second colon of a :: cast. */
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<!:):(?!:)\\w");

    @Autowired
    JdbcTemplate jdbc;

    private String prefix;
    private String seeded;

    @BeforeEach
    void seed() {
        prefix = "plan-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        seeded = prefix + (SKUS / 2);
        jdbc.update("INSERT INTO sku (sku_id) SELECT ? || g FROM generate_series(1, ?) g", prefix, SKUS);
        jdbc.update("""
                INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
                SELECT ? || g, 1, 'add' FROM generate_series(1, ?) g, generate_series(1, ?) r
                """, prefix, SKUS, ROWS_PER_SKU);
        jdbc.execute("ANALYZE inventory_ledger");
    }

    private String explain(String sql) {
        return String.join("\n", jdbc.queryForList("EXPLAIN " + sql, String.class));
    }

    @Test
    void sumQueryUsesSkuIndex() {
        String plan = explain(
                "SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = '" + seeded + "'");

        assertThat(plan).as(plan).contains("inventory_ledger_sku");
        assertThat(plan).as(plan).doesNotContain("Seq Scan on inventory_ledger");
    }

    /** Inlines the production statement's :id and :q so it can be EXPLAINed; fails if any named parameter is left. */
    private String inline(String statement) {
        assertThat(ID_PARAM.matcher(statement).find()).as(statement).isTrue();
        assertThat(Q_PARAM.matcher(statement).find()).as(statement).isTrue();
        String withId = ID_PARAM.matcher(statement).replaceAll(Matcher.quoteReplacement("'" + seeded + "'"));
        String sql = Q_PARAM.matcher(withId).replaceAll("3");
        assertThat(NAMED_PARAM.matcher(sql).find()).as(sql).isFalse();
        return sql;
    }

    @Test
    void addAndPurchaseStatementsUseSkuIndex() {
        String add = explain(inline(InventoryWritesImpl.ADD));
        String purchase = explain(inline(InventoryWritesImpl.PURCHASE));

        for (String plan : new String[] {add, purchase}) {
            assertThat(plan).as(plan).contains("inventory_ledger_sku");
            assertThat(plan).as(plan).doesNotContain("Seq Scan on inventory_ledger");
        }
    }

    /**
     * G9: the page query range-scans the sku primary key and sums each page row's ledger through
     * inventory_ledger_sku. The statement is read from the repository's @Query so the test tracks production.
     */
    @Test
    void pageQueryUsesIndexes() throws Exception {
        jdbc.execute("ANALYZE sku");
        String statement = SkuRepository.class.getMethod("findQuantitiesAfter", String.class, long.class)
                .getAnnotation(Query.class).value();
        String sql = statement.replace(":after", "'" + prefix + "'").replace(":limit", "3");
        assertThat(NAMED_PARAM.matcher(sql).find()).as(sql).isFalse();

        String plan = explain(sql);

        assertThat(plan).as(plan).contains("sku_pkey");
        assertThat(plan).as(plan).contains("inventory_ledger_sku");
        assertThat(plan).as(plan).doesNotContain("Seq Scan on inventory_ledger");
        assertThat(plan).as(plan).doesNotContain("Seq Scan on sku");
    }
}
