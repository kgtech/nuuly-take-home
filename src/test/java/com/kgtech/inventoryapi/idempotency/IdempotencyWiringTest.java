package com.kgtech.inventoryapi.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.resilience.retry.AbstractRetryInterceptor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Z1, X1, W2 wiring in the real context: the InventoryService proxy's advisors run [Retry, Idempotency, Tx], a
 * keyed 40001 retries the claim in a new transaction, and the store is reached only with a key. The store is a spy
 * on the real bean; requests go through MockMvc against Postgres. Not @Transactional; tables are emptied first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyWiringTest {

    @Autowired
    ApplicationContext context;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @MockitoSpyBean
    IdempotencyStore store;

    @BeforeEach
    void cleanTables() {
        // test-only deletes; the application never deletes key, ledger or sku rows (G5, R9)
        jdbc.sql("DELETE FROM idempotency_keys").update();
        jdbc.sql("DELETE FROM inventory_ledger").update();
        jdbc.sql("DELETE FROM sku").update();
    }

    private MockHttpServletResponse create(String skuId, int quantity, String key) throws Exception {
        var request = post("/inventory/{skuId}", skuId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":" + quantity + "}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private MockHttpServletResponse purchase(String skuId, int quantity, String key) throws Exception {
        var request = post("/inventory/{skuId}/purchase", skuId)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":" + quantity + "}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static int indexOf(List<Advisor> advisors, Predicate<Object> advice) {
        for (int i = 0; i < advisors.size(); i++) {
            if (advice.test(advisors.get(i).getAdvice())) {
                return i;
            }
        }
        return -1;
    }

    /** Z1: [Retry, Idempotency, Tx]; a lower-order retry advisor would run each retry inside one claim. */
    @Test
    void retryAdvisorWrapsIdempotencyAdvisor() {
        Object service = context.getBean("inventoryService");
        assertThat(AopUtils.isAopProxy(service)).isTrue();
        List<Advisor> advisors = List.of(((Advised) service).getAdvisors());

        int retry = indexOf(advisors, AbstractRetryInterceptor.class::isInstance);
        int idempotency = indexOf(advisors, IdempotencyInterceptor.class::isInstance);
        int transaction = indexOf(advisors, TransactionInterceptor.class::isInstance);

        assertThat(retry).as("retry advisor in %s", advisors).isNotNegative();
        assertThat(idempotency).as("idempotency advisor in %s", advisors).isNotNegative();
        assertThat(transaction).as("transaction advisor in %s", advisors).isNotNegative();
        assertThat(retry).as("retry before idempotency").isLessThan(idempotency);
        assertThat(idempotency).as("idempotency before @Transactional").isLessThan(transaction);
    }

    /**
     * W2, X1: the first attempt claims the key and writes the ledger row, then fails with 40001; the retry runs the
     * claim again in a new transaction, so the key is claimed (not replayed) and exactly one row of each is left.
     */
    @Test
    void retryWrapsClaim() throws Exception {
        CannotAcquireLockException serializationFailure =
                new CannotAcquireLockException("forced", new SQLException("forced", "40001"));
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw serializationFailure;
        }).doCallRealMethod().when(store).execute(any(), any());
        String key = UUID.randomUUID().toString();

        MockHttpServletResponse response = create("widget", 5, key);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"skuId\":\"widget\",\"quantity\":5}");
        verify(store, times(2)).execute(any(), any());
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM inventory_ledger")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_keys WHERE status = 200")).isEqualTo(1);
    }

    /** G8: without the header the store is never called. */
    @Test
    void unkeyedPostNeverCallsStore() throws Exception {
        assertThat(create("widget", 5, null).getStatus()).isEqualTo(200);
        assertThat(purchase("widget", 2, null).getStatus()).isEqualTo(200);
        assertThat(purchase("ghost", 1, null).getStatus()).isEqualTo(404);

        verifyNoInteractions(store);
        assertThat(count("SELECT count(*) FROM idempotency_keys")).isZero();
    }

    /** R2: a keyed POST goes through the store exactly once, and so does its replay. */
    @Test
    void keyedPostCallsStoreOnce() throws Exception {
        String key = UUID.randomUUID().toString();

        assertThat(create("widget", 5, key).getStatus()).isEqualTo(200);
        verify(store, times(1)).execute(any(), any());

        assertThat(create("widget", 5, key).getStatus()).isEqualTo(200);
        verify(store, times(2)).execute(any(), any());
        assertThat(count("SELECT count(*) FROM inventory_ledger")).isEqualTo(1);
    }
}
