package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcCoStarGraphRepositoryIntegrationTest {

    @Autowired
    JdbcCoStarGraphRepository repository;

    @Test
    void directCoStarsAreOneDegreeApart() {
        var path = repository.findShortestPath(1, 2).orElseThrow();

        assertThat(path.degree()).isEqualTo(1);
        assertThat(path.personIds()).containsExactly(1, 2);
    }

    @Test
    void findsTheShortestPathAcrossMultipleHopsOnBothSidesOfTheBidirectionalSearch() {
        // 1-2-3-4-5-6: five edges, so this exercises the bidirectional CTE meeting in the middle
        // (sideCap=4 per side, LLD §5.1/§5.2) rather than a single-hop lookup.
        var path = repository.findShortestPath(1, 6).orElseThrow();

        assertThat(path.degree()).isEqualTo(5);
        assertThat(path.personIds()).containsExactly(1, 2, 3, 4, 5, 6);
    }

    @Test
    void isOrderIndependent() {
        var forward = repository.findShortestPath(1, 6).orElseThrow();
        var backward = repository.findShortestPath(6, 1).orElseThrow();

        assertThat(forward.degree()).isEqualTo(backward.degree());
    }

    @Test
    void returnsEmptyWhenThePersonHasNoCoStars() {
        // person 7 is the only credited principal on their one title - co_star_edges has no row for
        // them at all.
        assertThat(repository.findShortestPath(1, 7)).isEmpty();
    }
}
