package com.kgtech.inventoryapi.idempotency;

import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import org.springframework.transaction.PlatformTransactionManager;

/** Registers the {@link Idempotent} advisor (Z1): programmatic spring-aop, no @Aspect. */
@Configuration(proxyBeanMethods = false)
class IdempotencyConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor idempotentAdvisor(ObjectProvider<IdempotencyStore> store,
            ObjectProvider<PlatformTransactionManager> transactionManager, ListableBeanFactory beanFactory) {
        return new DefaultPointcutAdvisor(new IdempotentMethodPointcut(),
                new IdempotencyInterceptor(store, transactionManager, beanFactory));
    }
}
