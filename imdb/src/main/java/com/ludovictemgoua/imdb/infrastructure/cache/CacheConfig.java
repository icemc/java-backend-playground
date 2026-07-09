package com.ludovictemgoua.imdb.infrastructure.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

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
        //
        // enableDefaultTyping is also opt-in here (automatic on the old serializer) - without it, the
        // serializer writes plain JSON with no type metadata at all, so every cache HIT (not miss)
        // deserializes to a raw LinkedHashMap instead of the original record and throws
        // ClassCastException - only surfaced once a real cache entry was actually read back on a second
        // request, since writing a cache entry never exercises the read path. Scoped to our own
        // packages (not enableUnsafeDefaultTyping's wide-open Object.class) since Redis is a trust
        // boundary in principle even though this deployment doesn't expose it externally.
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.ludovictemgoua.imdb.")
                .allowIfSubType("java.util.")
                .build();

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(24))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(GenericJacksonJsonRedisSerializer.builder()
                                .enableSpringCacheNullValueSupport()
                                .enableDefaultTyping(typeValidator)
                                .build()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }
}
