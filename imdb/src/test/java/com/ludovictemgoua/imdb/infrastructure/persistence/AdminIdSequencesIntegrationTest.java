package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class AdminIdSequencesIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    // No @Sql fixture load here, deliberately: V6 runs during Flyway migration, which - in this
    // Testcontainers environment - happens against a genuinely empty schema (fixture data loads
    // afterward, once the full Spring context, and therefore Flyway, is already up). The "never
    // collides with existing rows" guarantee V6 actually provides only holds when Flyway runs AFTER
    // the seed data already exists, which is the real production ordering (abanda/imdb-postgresql's
    // own import completes before imdb-service - and its Flyway migrations - ever start). Asserting
    // "greater than the fixture's max id" here would be asserting something this test's own ordering
    // can't guarantee; what's true in every environment is that the sequence exists and is usable.

    @Test
    void titleIdSequenceProducesIncreasingUsableValues() {
        Integer first = jdbc.queryForObject("SELECT nextval('title_id_seq')", Integer.class);
        Integer second = jdbc.queryForObject("SELECT nextval('title_id_seq')", Integer.class);

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void personIdSequenceProducesIncreasingUsableValues() {
        Integer first = jdbc.queryForObject("SELECT nextval('person_id_seq')", Integer.class);
        Integer second = jdbc.queryForObject("SELECT nextval('person_id_seq')", Integer.class);

        assertThat(second).isGreaterThan(first);
    }

    @Test
    void titleIdSequenceStartsAboveExistingRowsWhenMigratedAgainstAlreadySeededData() {
        // Simulates the real production ordering directly: insert a row with a high id (as if the
        // seed image's import had already run), drop and recreate the sequence the way V6 itself
        // does, and confirm nextval now correctly starts above it.
        jdbc.update("INSERT INTO title_basics (tconst, title_type, primary_title, original_title) " +
                "VALUES (999999, 'movie', 'Simulated Seeded Row', 'Simulated Seeded Row')");
        jdbc.update("DROP SEQUENCE title_id_seq");
        jdbc.execute("""
                DO $$
                DECLARE next_id BIGINT;
                BEGIN
                    SELECT COALESCE(max(tconst), 0) + 1 INTO next_id FROM title_basics;
                    EXECUTE format('CREATE SEQUENCE title_id_seq START WITH %s', next_id);
                END $$;
                """);

        Integer nextVal = jdbc.queryForObject("SELECT nextval('title_id_seq')", Integer.class);

        assertThat(nextVal).isGreaterThan(999999);
    }
}
