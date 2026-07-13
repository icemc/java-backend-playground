package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcTitleRepositoryIntegrationTest {

    @Autowired
    JdbcTitleRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void searchFindsATitleByFuzzyPrimaryTitleMatch() {
        var results = repository.search("Few Good Men", 0, 20);

        assertThat(results.content()).extracting("id").contains("tt0000100");
    }

    @Test
    void findCoreReturnsMetadataAndRating() {
        var core = repository.findCore(100).orElseThrow();

        assertThat(core.primaryTitle()).isEqualTo("A Few Good Men");
        assertThat(core.averageRating()).isEqualTo(8.0);
        assertThat(core.numVotes()).isEqualTo(500000);
        assertThat(core.genres()).containsExactly("Drama");
    }

    @Test
    void findCoreIsEmptyForUnknownTitle() {
        assertThat(repository.findCore(987654)).isEmpty();
    }

    @Test
    void findDirectorsAndWritersUnnestTheCrewArrays() {
        assertThat(repository.findDirectors(100)).extracting("name").containsExactly("Rob Reiner");
        assertThat(repository.findWriters(100)).extracting("name").containsExactly("Aaron Sorkin");
    }

    @Test
    void findTopCastOrdersByBillingAndCountCastMatchesTotal() {
        var cast = repository.findTopCast(100, 20);

        assertThat(cast).extracting("name").containsExactly("Kevin Bacon", "Tom Cruise");
        assertThat(repository.countCast(100)).isEqualTo(2);
    }

    @Test
    void findAnyCommonTitleFindsTheSharedCredit() {
        var shared = repository.findAnyCommonTitle(1, 2).orElseThrow();

        assertThat(shared.primaryTitle()).isEqualTo("A Few Good Men");
    }

    @Test
    void findAnyCommonTitleIsEmptyWhenTheyNeverCoStarred() {
        assertThat(repository.findAnyCommonTitle(1, 6)).isEmpty();
    }

    @Test
    void findTopRatedRanksByWeightedRatingNotRawAverage() {
        // 201's raw average (10.0) beats 200's (8.9), but at minVotes=100 the Bayesian shrinkage
        // (PDD §9) pulls 201 down toward the pool mean enough that 200 - backed by 500,000 votes -
        // still ranks first. See the fixture comment for the arithmetic this depends on.
        var topRated = repository.findTopRated("Action", 10, 100);

        assertThat(topRated).extracting("id").startsWith("tt0000200", "tt0000201");
    }

    @Test
    void findCoreExcludesASoftDeletedTitle() {
        jdbc.update("UPDATE title_basics SET deleted_at = now() WHERE tconst = 100");

        assertThat(repository.findCore(100)).isEmpty();
    }

    @Test
    void searchExcludesASoftDeletedTitle() {
        jdbc.update("UPDATE title_basics SET deleted_at = now() WHERE tconst = 100");

        assertThat(repository.search("Few Good Men", 0, 20).content()).extracting("id").doesNotContain("tt0000100");
    }

    @Test
    void insertTitleCreatesARowWithVersionZero() {
        var created = repository.insertTitle("New Movie", "New Movie", "movie", 2024, null, 120, List.of("Drama"));

        assertThat(created.version()).isEqualTo(0);
        assertThat(repository.findCore(ImdbIds.parseTitleId(created.id())).orElseThrow().primaryTitle())
                .isEqualTo("New Movie");
    }

    @Test
    void insertedTitleIdIsAboveTheSeededRange() {
        var created = repository.insertTitle("Another Movie", "Another Movie", "movie", 2024, null, 90, List.of());

        assertThat(ImdbIds.parseTitleId(created.id())).isGreaterThan(200);
    }

    @Test
    void updateTitleBumpsVersionAndPersists() {
        var created = repository.insertTitle("Old Name", "Old Name", "movie", 2020, null, 100, List.of("Drama"));
        int tconst = ImdbIds.parseTitleId(created.id());

        var result = repository.updateTitle(
                tconst, "New Name", "New Name", "movie", 2021, null, 110, List.of("Comedy"), created.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findCore(tconst).orElseThrow();
        assertThat(updated.primaryTitle()).isEqualTo("New Name");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void updateTitleReturnsVersionConflictOnStaleVersion() {
        var created = repository.insertTitle("Stale Test", "Stale Test", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        var result = repository.updateTitle(
                tconst, "New Name", "New Name", "movie", 2021, null, 110, List.of(), created.version() + 1);

        assertThat(result).isEqualTo(WriteResult.VERSION_CONFLICT);
    }

    @Test
    void softDeleteTitleExcludesItFromFindCore() {
        var created = repository.insertTitle("Delete Me", "Delete Me", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        repository.softDeleteTitle(tconst);

        assertThat(repository.findCore(tconst)).isEmpty();
    }

    @Test
    void upsertRatingThenDeleteRatingRoundTrips() {
        var created = repository.insertTitle("Rating Test", "Rating Test", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        repository.upsertRating(tconst, 7.5, 1000);
        assertThat(repository.findCore(tconst).orElseThrow().averageRating()).isEqualTo(7.5);

        repository.deleteRating(tconst);
        assertThat(repository.findCore(tconst).orElseThrow().averageRating()).isNull();
    }

    @Test
    void insertPrincipalThenFindAllPrincipalsIncludesIt() {
        var result = repository.insertPrincipal(100, 1, "actor", null, List.of("New Role"), 99);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        assertThat(repository.findAllPrincipals(100)).extracting("ordering").contains(99);
    }

    @Test
    void updatePrincipalBumpsVersionAndPersists() {
        repository.insertPrincipal(100, 1, "actor", null, List.of("Original"), 98);

        var result = repository.updatePrincipal(100, 98, "actor", null, List.of("Updated"), 0);

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findAllPrincipals(100).stream()
                .filter(p -> p.ordering() == 98).findFirst().orElseThrow();
        assertThat(updated.characters()).containsExactly("Updated");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void softDeletePrincipalExcludesItFromFindAllPrincipals() {
        repository.insertPrincipal(100, 1, "actor", null, List.of("Temp"), 97);

        repository.softDeletePrincipal(100, 97);

        assertThat(repository.findAllPrincipals(100)).extracting("ordering").doesNotContain(97);
    }
}
