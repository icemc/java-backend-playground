package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CacheConfigIntegrationTest {

    @Autowired
    RedisCacheManager cacheManager;

    @Test
    void titleSearchCacheHasAFifteenMinuteTtlNotTheDefaultTwentyFourHours() {
        var config = ((RedisCache) cacheManager.getCache("title-search")).getCacheConfiguration();

        assertThat(config.getTtlFunction().getTimeToLive("key", "value")).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void titleDetailCacheKeepsTheDefaultTwentyFourHourTtl() {
        var config = ((RedisCache) cacheManager.getCache("title-detail")).getCacheConfiguration();

        assertThat(config.getTtlFunction().getTimeToLive("key", "value")).isEqualTo(Duration.ofHours(24));
    }
}
