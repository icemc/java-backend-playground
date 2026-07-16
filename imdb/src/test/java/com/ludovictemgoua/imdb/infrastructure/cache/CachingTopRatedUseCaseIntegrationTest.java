package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// See CachingTitleSearchUseCaseIntegrationTest for why this exists alongside the unit-level
// CachingTopRatedUseCaseTest - this exercises the real Redis serialization round trip that a
// ConcurrentMapCacheManager-backed unit test structurally cannot.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CachingTopRatedUseCaseIntegrationTest {

    @Autowired
    TopRatedUseCase topRatedUseCase;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("top-rated").clear();
    }

    @Test
    void secondCallIsServedFromRealRedisAndDeserializesToAnEqualResult() {
        var first = topRatedUseCase.findTopRated("Action", 10, 100);
        assertThat(first).extracting("id").startsWith("tt0000200", "tt0000201");
        assertThat(cacheManager.getCache("top-rated").get("Action:10:100")).isNotNull();

        var second = topRatedUseCase.findTopRated("Action", 10, 100);

        assertThat(second).isEqualTo(first);
    }
}
