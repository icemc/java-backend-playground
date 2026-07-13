package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

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
}
