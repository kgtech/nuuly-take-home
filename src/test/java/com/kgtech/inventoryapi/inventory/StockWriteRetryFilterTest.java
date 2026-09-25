package com.kgtech.inventoryapi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.kgtech.inventoryapi.TestcontainersConfiguration;

/**
 * Y2 at the interceptor: the ledger write is mocked to throw a PessimisticLockingFailureException, so it passes the
 * {@code includes} filter and only the SerializationFailure predicate decides. The service's real @Retryable proxy and
 * SERIALIZABLE TransactionTemplate run. Not @Transactional.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StockWriteRetryFilterTest {

    @MockitoBean
    SkuRepository skus;

    @Autowired
    InventoryService service;

    @Test
    void lockTimeoutAsPessimisticLockingFailureIsNotRetried() {
        CannotAcquireLockException lockTimeout = new CannotAcquireLockException("x", new SQLException("x", "55P03"));
        when(skus.add("filter-55p03", 4)).thenThrow(lockTimeout);

        assertThatThrownBy(() -> service.add("filter-55p03", 4)).isSameAs(lockTimeout);

        verify(skus, times(1)).add("filter-55p03", 4);
    }

    @Test
    void serializationFailureAsPessimisticLockingFailureIsRetried() {
        when(skus.add("filter-40001", 4))
                .thenThrow(new CannotAcquireLockException("x", new SQLException("x", "40001")))
                .thenReturn(new StockOutcome.Ok(5));

        assertThat(service.add("filter-40001", 4)).isEqualTo(new StockOutcome.Ok(5));

        verify(skus, times(2)).add("filter-40001", 4);
    }
}
