package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

// Complements the unit-level CachingTitleSearchUseCaseTest (ConcurrentMapCacheManager, mocked
// delegate) - that one proves the @Cacheable wiring is correct, but an in-memory map never
// serializes anything, so it structurally cannot catch a bug in the real CacheConfig/
// RedisCacheManager path. This runs the real stack end to end (real repository, real Postgres
// fixture data, real Redis via Testcontainers) so the second call is a genuine deserialize-from-
// Redis read, not a Java reference handed back from a map - exactly the class of bug that surfaced
// live earlier (GenericJacksonJsonRedisSerializer missing type metadata -> ClassCastException on a
// cache hit) and that the unit test alone could never have caught.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CachingTitleSearchUseCaseIntegrationTest {

    @Autowired
    TitleSearchUseCase titleSearchUseCase;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("title-search").clear();
    }

    @Test
    void secondCallIsServedFromRealRedisAndDeserializesToAnEqualResult() {
        var first = titleSearchUseCase.search("Few Good Men", 0, 20);
        assertThat(first.content()).extracting("id").contains("tt0000100");
        assertThat(cacheManager.getCache("title-search").get("Few Good Men:0:20")).isNotNull();

        var second = titleSearchUseCase.search("Few Good Men", 0, 20);

        assertThat(second).isEqualTo(first);
    }
}
