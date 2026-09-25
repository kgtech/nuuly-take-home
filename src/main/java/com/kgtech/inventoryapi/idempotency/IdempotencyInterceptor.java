package com.kgtech.inventoryapi.idempotency;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Applies {@link Idempotent} (Z1): no key → proceed; malformed key → invalidRequest; beforeClaim rejection → returned;
 * otherwise claim, write and store in one SERIALIZABLE REQUIRES_NEW transaction (X1, R2).
 */
final class IdempotencyInterceptor implements MethodInterceptor {

    private final ObjectProvider<IdempotencyStore> store;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;
    private final ListableBeanFactory beanFactory;

    IdempotencyInterceptor(ObjectProvider<IdempotencyStore> store,
            ObjectProvider<PlatformTransactionManager> transactionManager, ListableBeanFactory beanFactory) {
        this.store = store;
        this.transactionManager = transactionManager;
        this.beanFactory = beanFactory;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        throw new UnsupportedOperationException("not implemented");
    }
}
