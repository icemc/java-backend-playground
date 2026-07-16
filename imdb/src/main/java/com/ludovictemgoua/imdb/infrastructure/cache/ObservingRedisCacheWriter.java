package com.ludovictemgoua.imdb.infrastructure.cache;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.data.redis.cache.CacheStatistics;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnection;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

// Every method here delegates straight through to the real writer unchanged - the only reason this
// class exists is to tag the currently active Observation with cache.result=hit/miss right where
// get() finds out which one happened, information the cache.gets Counter (CacheConfig) already
// tracks in aggregate but doesn't attach to a trace. In practice the "currently active" observation
// at that point is the enclosing controller span (WebMvcTracingConfig), not the specific Redis GET
// span underneath it - Lettuce's own span (LettuceObservationAutoConfiguration, Boot 4.1's built-in
// wiring, confirmed active via a live trace with no extra config needed) closes synchronously inside
// the delegate.get(...) call above, before this method gets a chance to tag anything. Still useful:
// a trace shows the hit/miss outcome and the Redis/DB timing breakdown together, just not literally
// on the same span.
final class ObservingRedisCacheWriter implements RedisCacheWriter {

    private static final String CACHE_RESULT_TAG = "cache.result";

    private final RedisCacheWriter delegate;
    private final ObservationRegistry observationRegistry;

    ObservingRedisCacheWriter(RedisCacheWriter delegate, ObservationRegistry observationRegistry) {
        this.delegate = delegate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public byte[] get(String name, byte[] key) {
        byte[] value = delegate.get(name, key);
        tagCacheResult(value != null);
        return value;
    }

    private void tagCacheResult(boolean hit) {
        Observation current = observationRegistry.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue(CACHE_RESULT_TAG, hit ? "hit" : "miss");
        }
    }

    @Override
    public byte[] get(String name, byte[] key, Duration ttlFunction) {
        return delegate.get(name, key, ttlFunction);
    }

    @Override
    public byte[] get(String name, byte[] key, Supplier<byte[]> valueLoader, Duration ttl, boolean allowNullValues) {
        return delegate.get(name, key, valueLoader, ttl, allowNullValues);
    }

    @Override
    public boolean supportsAsyncRetrieve() {
        return delegate.supportsAsyncRetrieve();
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key) {
        return delegate.retrieve(name, key);
    }

    @Override
    public CompletableFuture<byte[]> retrieve(String name, byte[] key, Duration ttl) {
        return delegate.retrieve(name, key, ttl);
    }

    @Override
    public void put(String name, byte[] key, byte[] value, Duration ttl) {
        delegate.put(name, key, value, ttl);
    }

    @Override
    public CompletableFuture<Void> store(String name, byte[] key, byte[] value, Duration ttl) {
        return delegate.store(name, key, value, ttl);
    }

    @Override
    public byte[] putIfAbsent(String name, byte[] key, byte[] value, Duration ttl) {
        return delegate.putIfAbsent(name, key, value, ttl);
    }

    @Override
    public void remove(String name, byte[] key) {
        delegate.remove(name, key);
    }

    @Override
    public void evict(String name, byte[] key) {
        delegate.evict(name, key);
    }

    @Override
    public boolean evictIfPresent(String name, byte[] key) {
        return delegate.evictIfPresent(name, key);
    }

    @Override
    public void clean(String name, byte[] pattern) {
        delegate.clean(name, pattern);
    }

    @Override
    public void clear(String name, byte[] pattern) {
        delegate.clear(name, pattern);
    }

    @Override
    public boolean invalidate(String name, byte[] key) {
        return delegate.invalidate(name, key);
    }

    @Override
    public void clearStatistics(String name) {
        delegate.clearStatistics(name);
    }

    @Override
    public <T> T execute(Function<RedisConnection, T> callback) {
        return delegate.execute(callback);
    }

    @Override
    public RedisCacheWriter withStatisticsCollector(org.springframework.data.redis.cache.CacheStatisticsCollector cacheStatisticsCollector) {
        return delegate.withStatisticsCollector(cacheStatisticsCollector);
    }

    @Override
    public CacheStatistics getCacheStatistics(String cacheName) {
        return delegate.getCacheStatistics(cacheName);
    }
}
