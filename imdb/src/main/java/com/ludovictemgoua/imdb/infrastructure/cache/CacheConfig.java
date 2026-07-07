package com.ludovictemgoua.imdb.infrastructure.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // GenericJacksonJsonRedisSerializer, not GenericJackson2JsonRedisSerializer: Boot 4.1's default
        // Jackson is Jackson 3 (tools.jackson.databind.*, a different Maven groupId/package than the
        // Jackson 2.x com.fasterxml.jackson.databind.* the "2"-suffixed serializer needs - the latter
        // fails at runtime with a ClassNotFoundException since Jackson 2 isn't on the classpath at all
        // by default anymore). enableSpringCacheNullValueSupport() is opt-in here (it was automatic on
        // the old serializer's default constructor) - needed since caching a "no path found" result as
        // null is deliberate (LLD §6).
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(24))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.builder()
                                .enableSpringCacheNullValueSupport()
                                .build()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }
}
