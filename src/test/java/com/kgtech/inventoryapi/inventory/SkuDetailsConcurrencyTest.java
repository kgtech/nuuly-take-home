package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;

/** Issue #71 AC3 (DESIGN-V2 §8 "Create"): concurrent creates of one id, and a create racing the spec's add. */
@IntegrationTest
class SkuDetailsConcurrencyTest {

    private static final int THREADS = 8;
    private static final SkuDetails DETAILS = new SkuDetails("Linen shirt", "", Optional.empty(), List.of());

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    private long count(String sql, String sku) {
        return jdbc.sql(sql).param(sku).query(Long.class).single();
    }

    @Test
    void concurrentCreatesOfOneIdGiveOneCreatedAndTheRest409() throws InterruptedException {
        String sku = "race-" + UUID.randomUUID().toString().substring(0, 8);

        List<WriteResult> results = Concurrently.run(THREADS,
                () -> service.create(sku, new CreateSku(DETAILS, 3), null));

        assertThat(results).filteredOn(DetailsOutcome.Created.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(DetailsOutcome.AlreadyExists.class::isInstance).hasSize(THREADS - 1);
        assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(1);
        assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(3);
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void aCreateAndTheSpecAddOnOneNewIdBothLand() throws InterruptedException {
        String sku = "race-add-" + UUID.randomUUID().toString().substring(0, 8);
        AtomicInteger turn = new AtomicInteger();

        List<WriteResult> results = Concurrently.run(2, () -> turn.getAndIncrement() == 0
                ? service.create(sku, new CreateSku(DETAILS, 5), null)
                : service.add(sku, 2, null));

        WriteResult created = results.stream().filter(DetailsOutcome.class::isInstance).findFirst().orElseThrow();
        WriteResult added = results.stream().filter(StockOutcome.class::isInstance).findFirst().orElseThrow();
        assertThat(added).isInstanceOf(StockOutcome.Ok.class);
        // Either order: the add lands on the created SKU (details kept) or the create finds the SKU and answers 409.
        if (created instanceof DetailsOutcome.Created) {
            assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(7);
            assertThat(count("SELECT count(*) FROM inventory_ledger WHERE sku_id = ?", sku)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isEqualTo(1);
        } else {
            assertThat(created).isInstanceOf(DetailsOutcome.AlreadyExists.class);
            assertThat(count("SELECT quantity FROM sku WHERE sku_id = ?", sku)).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM sku_details WHERE sku_id = ?", sku)).isZero();
        }
        assertThat(Invariants.balanceMismatches(jdbc)).isEmpty();
    }

    @Test
    void concurrentConditionalPutsApplyExactlyOne() throws InterruptedException {
        assertThat(service.create("edit-1", new CreateSku(DETAILS, 1), null)).isInstanceOf(DetailsOutcome.Created.class);
        DetailsPrecondition version1 = new DetailsPrecondition.Versions(List.of(1L));
        AtomicInteger n = new AtomicInteger();

        List<ReplaceResult> results = Concurrently.run(THREADS, () -> service.replaceDetails("edit-1",
                new SkuDetails("Edit " + n.getAndIncrement(), "", Optional.empty(), List.of()), version1));

        assertThat(results).filteredOn(ReplaceResult.Replaced.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(ReplaceResult.VersionMismatch.class::isInstance).hasSize(THREADS - 1);
        assertThat(count("SELECT version FROM sku_details WHERE sku_id = ?", "edit-1")).isEqualTo(2);
    }
}
