package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
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
// CachingTitleDetailUseCaseTest - this exercises the real Redis serialization round trip that a
// ConcurrentMapCacheManager-backed unit test structurally cannot.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CachingTitleDetailUseCaseIntegrationTest {

    @Autowired
    TitleDetailUseCase titleDetailUseCase;

    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("title-detail").clear();
    }

    @Test
    void secondCallIsServedFromRealRedisAndDeserializesToAnEqualResult() {
        var first = titleDetailUseCase.getDetail("tt0000100");
        assertThat(first.primaryTitle()).isEqualTo("A Few Good Men");
        assertThat(cacheManager.getCache("title-detail").get("tt0000100")).isNotNull();

        var second = titleDetailUseCase.getDetail("tt0000100");

        assertThat(second).isEqualTo(first);
    }
}
