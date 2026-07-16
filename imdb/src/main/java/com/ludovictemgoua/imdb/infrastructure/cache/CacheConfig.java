package com.ludovictemgoua.imdb.infrastructure.cache;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

import java.time.Duration;
import java.util.Set;

@Configuration
@EnableCaching
public class CacheConfig {

    // The four @Cacheable cacheNames (LLD §6). Passed to initialCacheNames below so each region
    // exists in the manager from startup rather than being created lazily on its first @Cacheable
    // call - required for cacheStatisticsMeterBinder below to have something to read immediately,
    // and (independently) for RedisCacheManager.getCacheNames() to ever report these regions at all.
    private static final Set<String> CACHE_NAMES =
            Set.of("title-search", "title-detail", "top-rated", "six-degrees");

    @Bean
    public RedisCacheWriter redisCacheWriter(RedisConnectionFactory connectionFactory,
                                              ObservationRegistry observationRegistry) {
        // .collectStatistics() turns on Spring Data Redis's own CacheStatisticsCollector (gets/hits/
        // misses/puts per cache name) - the same native source Spring Boot's now-removed
        // CacheMetricsRegistrar/RedisCacheMeterBinderProvider used to read automatically pre-Boot-4.1
        // (confirmed absent by decompiling spring-boot-actuator-autoconfigure-4.1.0.jar: no "cache"
        // package exists in it at all anymore). cacheStatisticsMeterBinder below is the manual
        // replacement for that removed auto-binding, reading from this same writer.
        RedisCacheWriter writer = RedisCacheWriter.create(connectionFactory,
                RedisCacheWriter.RedisCacheWriterConfigurer::collectStatistics);
        // Wrapped so every cache lookup also tags the current trace span with cache.result=hit/miss
        // (tracing-design.md) - the same hit/miss signal the Counters above already track in
        // aggregate, attached to a single request's trace instead.
        return new ObservingRedisCacheWriter(writer, observationRegistry);
    }

    @Bean
    public MeterBinder cacheStatisticsMeterBinder(RedisCacheWriter redisCacheWriter) {
        return registry -> CACHE_NAMES.forEach(name -> {
            FunctionCounter.builder("cache.gets", redisCacheWriter,
                            writer -> writer.getCacheStatistics(name).getHits())
                    .tag("cache", name).tag("result", "hit")
                    .register(registry);
            FunctionCounter.builder("cache.gets", redisCacheWriter,
                            writer -> writer.getCacheStatistics(name).getMisses())
                    .tag("cache", name).tag("result", "miss")
                    .register(registry);
            FunctionCounter.builder("cache.puts", redisCacheWriter,
                            writer -> writer.getCacheStatistics(name).getPuts())
                    .tag("cache", name)
                    .register(registry);
        });
    }

    @Bean
    public RedisCacheManager cacheManager(RedisCacheWriter redisCacheWriter) {
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

        // title-search is keyed by query:page:size (LLD §6) - an admin title write has no cheap way
        // to know which of those arbitrary combinations it affects, so precise eviction (like
        // title-detail) isn't possible and full-region eviction on every write would defeat the
        // cache almost entirely. Accept a bounded staleness window instead (design doc §6.2).
        //
        // withCacheConfiguration must come after initialCacheNames: initialCacheNames registers every
        // name in CACHE_NAMES against cacheDefaults, so calling it afterward would silently overwrite
        // this override back to the 24h default.
        RedisCacheConfiguration searchCacheConfig = defaults.entryTtl(Duration.ofMinutes(15));

        return RedisCacheManager.builder(redisCacheWriter)
                .cacheDefaults(defaults)
                .initialCacheNames(CACHE_NAMES)
                .withCacheConfiguration("title-search", searchCacheConfig)
                .build();
    }
}
