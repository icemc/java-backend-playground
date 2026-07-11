package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
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
// CachingCoStarGraphRepositoryTest - this exercises the real Redis serialization round trip that a
// ConcurrentMapCacheManager-backed unit test structurally cannot.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CachingCoStarGraphRepositoryIntegrationTest {

    @Autowired
    CoStarGraphRepository coStarGraphRepository;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("six-degrees").clear();
    }

    @Test
    void secondCallIsServedFromRealRedisAndDeserializesToAnEqualResult() {
        var first = coStarGraphRepository.findShortestPath(1, 2).orElseThrow();
        assertThat(first.degree()).isEqualTo(1);
        assertThat(cacheManager.getCache("six-degrees").get("1-2")).isNotNull();

        var second = coStarGraphRepository.findShortestPath(1, 2).orElseThrow();

        assertThat(second).isEqualTo(first);
    }
}
