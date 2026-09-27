package com.kgtech.inventoryapi.cache;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

/** Wires the Redis caches (DESIGN-V2 §1). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
class CacheConfiguration {

    /** The atomic write of a replay copy with its TTL (DESIGN-V2 §2). */
    @Bean
    RedisScript<Long> replayPutScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/replay-put.lua")));
        script.setResultType(Long.class);
        return script;
    }

    /** The versioned, TTL-setting write of a stock count (DESIGN-V2 §3), loaded once and EVALSHA'd by Redis. */
    @Bean
    RedisScript<Long> stockSetScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/stock-set.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
