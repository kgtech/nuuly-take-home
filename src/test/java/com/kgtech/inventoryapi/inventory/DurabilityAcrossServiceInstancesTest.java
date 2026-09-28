package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.kgtech.inventoryapi.InventoryApplication;
import com.kgtech.inventoryapi.IntegrationTest;
import com.kgtech.inventoryapi.Tables;
import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Durable (DESIGN-V2 §3 invariants, issue #35, critique V-08): once this instance has acknowledged an add and a
 * purchase, a second, freshly started service instance against the same Postgres reports the same quantity and the
 * balance equals the ledger. Nothing the first instance held in memory is needed (DESIGN-V2 §9: nothing is cached).
 */
@IntegrationTest
class DurabilityAcrossServiceInstancesTest {

    @Autowired
    InventoryService service;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void clean() {
        Tables.reset(jdbc);
    }

    @Test
    void aFreshInstanceReportsTheAcknowledgedStock() {
        assertThat(service.add("durable", 10, null)).isEqualTo(new StockOutcome.Ok(10));
        assertThat(service.purchase("durable", 3, null)).isEqualTo(new StockOutcome.Ok(7));
        assertThat(service.find("durable")).contains(new InventoryItem("durable", 7));

        // A real second instance: the full application on its own random port, configured by connection properties
        // only (not the test configuration, whose Testcontainers lifecycle would stop the shared containers on close).
        try (ConfigurableApplicationContext fresh = new SpringApplicationBuilder(InventoryApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(TestcontainersConfiguration.connectionProperties())
                .properties("server.port=0")
                .run()) {
            InventoryService second = fresh.getBean(InventoryService.class);
            JdbcClient secondJdbc = fresh.getBean(JdbcClient.class);

            assertThat(second.find("durable")).contains(new InventoryItem("durable", 7));
            assertThat(second.list(null, null).items()).containsExactly(new InventoryItem("durable", 7));
            assertThat(Invariants.balanceMismatches(secondJdbc)).isEmpty();
            assertThat(secondJdbc.sql("SELECT count(*) FROM inventory_ledger WHERE sku_id = 'durable'")
                    .query(Long.class).single()).isEqualTo(2);
        }
    }
}
