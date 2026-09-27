package com.kgtech.inventoryapi.idempotency;

import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.kgtech.inventoryapi.cache.AfterCommit;

/**
 * Applies {@link Idempotent} (Z1): no key → proceed; malformed key → invalidRequest; beforeClaim rejection → returned;
 * then the Redis replay cache (DESIGN-V2 §2 step 2); otherwise claim, write and store in one READ COMMITTED
 * REQUIRES_NEW transaction (R2 as superseded), and copy the completed response to Redis after commit.
 */
final class IdempotencyInterceptor implements MethodInterceptor {

    private final ObjectProvider<IdempotencyStore> store;
    private final ObjectProvider<ReplayCache> replayCache;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;
    private final ListableBeanFactory beanFactory;
    private final Map<Method, IdempotentResults<Object>> results = new ConcurrentHashMap<>();
    private volatile TransactionTemplate requiresNew;

    IdempotencyInterceptor(ObjectProvider<IdempotencyStore> store, ObjectProvider<ReplayCache> replayCache,
            ObjectProvider<PlatformTransactionManager> transactionManager, ListableBeanFactory beanFactory) {
        this.store = store;
        this.replayCache = replayCache;
        this.transactionManager = transactionManager;
        this.beanFactory = beanFactory;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object[] arguments = invocation.getArguments();
        String rawKey = (String) arguments[2];
        if (rawKey == null) {
            return invocation.proceed(); // G8
        }
        Method method = invocation.getMethod();
        IdempotentResults<Object> results = resultsFor(method);
        if (!IdempotencyKey.isValid(rawKey)) {
            return results.invalidRequest(); // S3: "" is a present key; not stored
        }
        Operation operation = operationFor(method);
        String skuId = (String) arguments[0];
        Optional<Object> rejected = results.beforeClaim(operation, skuId); // S2, U3
        if (rejected.isPresent()) {
            return rejected.get();
        }
        IdempotentRequest request =
                new IdempotentRequest(IdempotencyKey.parse(rawKey), operation, skuId, (Integer) arguments[1]);
        ReplayCache cache = replayCache.getObject();
        KeyedResult result = cache.lookup(request).orElseGet(() -> requiresNew().execute(status -> {
            KeyedResult keyed = store.getObject().execute(request, () -> results.toStored(skuId, proceed(invocation)));
            switch (keyed) {
                case KeyedResult.Executed executed ->
                        AfterCommit.run(() -> cache.put(request, executed.response(), executed.createdAt()));
                case KeyedResult.Replayed replayed ->
                        AfterCommit.run(() -> cache.put(request, replayed.response(), replayed.createdAt()));
                case KeyedResult.Rejected _ -> { }
            }
            return keyed;
        }));
        return switch (result) {
            case KeyedResult.Executed executed -> results.stored(executed.response());
            case KeyedResult.Replayed replayed -> results.stored(replayed.response());
            case KeyedResult.Rejected _ -> results.invalidRequest(); // S8, T1
        };
    }

    /** Runtime exceptions escape unchanged so the transaction rolls back and the advice answers 500. */
    private static Object proceed(MethodInvocation invocation) {
        try {
            return invocation.proceed();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new UndeclaredThrowableException(e);
        }
    }

    private static Operation operationFor(Method method) {
        Idempotent idempotent = AnnotatedElementUtils.findMergedAnnotation(method, Idempotent.class);
        if (idempotent == null) {
            throw new IllegalStateException(method + " is not @Idempotent");
        }
        return idempotent.value();
    }

    @SuppressWarnings("unchecked")
    private IdempotentResults<Object> resultsFor(Method method) {
        return results.computeIfAbsent(method, m -> (IdempotentResults<Object>) beanFactory
                .getBeanProvider(ResolvableType.forClassWithGenerics(IdempotentResults.class, m.getReturnType()))
                .getObject());
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate template = requiresNew;
        if (template == null) {
            template = new TransactionTemplate(transactionManager.getObject());
            template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            requiresNew = template;
        }
        return template;
    }
}
