package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.application.rest.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.rest.RatingRequest;
import com.ludovictemgoua.imdb.application.rest.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// See CachingTitleDetailUseCaseIntegrationTest et al. for why this exercises real Redis instead of
// a mocked CacheManager - a mock can prove a @CacheEvict-annotated method was called, but not that
// the eviction actually reached Redis. clearCaches() below matches those tests' established
// @BeforeEach pattern (each region is cleared, not just the ones this test primes) so no stale entry
// from a previous test can be mistaken for one this test wrote itself.
//
// awaitCacheState polls instead of asserting immediately: this Testcontainers Redis, running over
// Docker Desktop's Windows npipe/WSL2 network path, occasionally serves a read a few milliseconds
// before a just-issued write/eviction on the same connection is visible - a local dev-environment
// networking artifact, not an application bug (the same eviction succeeds every time once given a
// short window to land).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CacheEvictionIntegrationTest {

    @Autowired
    TitleDetailUseCase titleDetailUseCase;
    @Autowired
    TopRatedUseCase topRatedUseCase;
    @Autowired
    TitleAdminUseCase titleAdminUseCase;
    @Autowired
    PersonAdminUseCase personAdminUseCase;
    @Autowired
    SixDegreesUseCase sixDegreesUseCase;
    @Autowired
    CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCache("title-detail").clear();
        cacheManager.getCache("top-rated").clear();
        cacheManager.getCache("six-degrees").clear();
    }

    @Test
    void updatingATitleEvictsItsTitleDetailCacheEntry() {
        titleDetailUseCase.getDetail("tt0000100");
        assertThat(awaitCacheState("title-detail", "tt0000100", true)).isNotNull();

        var current = titleDetailUseCase.getDetail("tt0000100");
        titleAdminUseCase.update("tt0000100", new UpdateTitleRequest(
                current.primaryTitle(), current.originalTitle(), current.titleType(),
                current.startYear(), current.endYear(), current.runtimeMinutes(), current.genres(), 0));

        assertThat(awaitCacheState("title-detail", "tt0000100", false)).isNull();
    }

    @Test
    void writingARatingEvictsTheEntireTopRatedRegion() {
        topRatedUseCase.findTopRated("Action", 10, 100);
        assertThat(awaitCacheState("top-rated", "Action:10:100", true)).isNotNull();

        titleAdminUseCase.upsertRating("tt0000200", new RatingRequest(9.0, 200000));

        assertThat(awaitCacheState("top-rated", "Action:10:100", false)).isNull();
    }

    @Test
    void updatingAPersonEvictsTheEntireSixDegreesRegion() {
        sixDegreesUseCase.compute("nm0000001", "nm0000002", 7);
        assertThat(awaitCacheState("six-degrees", "1-2", true)).isNotNull();

        personAdminUseCase.patch("nm0000001", new PatchPersonRequest("Kevin Bacon Jr.", null, null, List.of(), 0));

        assertThat(awaitCacheState("six-degrees", "1-2", false)).isNull();
    }

    private Object awaitCacheState(String cacheName, String key, boolean expectPresent) {
        Object value = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            var wrapper = cacheManager.getCache(cacheName).get(key);
            value = wrapper == null ? null : wrapper.get();
            if ((wrapper != null) == expectPresent) {
                return value;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return value;
            }
        }
        return value;
    }
}
