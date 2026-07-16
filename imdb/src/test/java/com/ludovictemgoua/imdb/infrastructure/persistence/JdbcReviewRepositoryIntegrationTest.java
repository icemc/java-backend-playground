package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcReviewRepositoryIntegrationTest {

    @Autowired
    JdbcReviewRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void insertThenFindByUserAndTitleRoundTrips() {
        int userId = userRepository.insert("reviewer1@example.com", "hash", "Reviewer", Role.USER).id();

        var review = repository.insert(userId, 100, 9, "Great film");

        var found = repository.findByUserAndTitle(userId, 100).orElseThrow();
        assertThat(found.id()).isEqualTo(review.id());
        assertThat(found.rating()).isEqualTo(9);
        assertThat(found.version()).isEqualTo(0);
    }

    @Test
    void updateBumpsVersionAndPersists() {
        int userId = userRepository.insert("reviewer2@example.com", "hash", "Reviewer", Role.USER).id();
        var review = repository.insert(userId, 100, 5, "Meh");

        var result = repository.update(review.id(), 8, "Actually great", review.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findByUserAndTitle(userId, 100).orElseThrow();
        assertThat(updated.rating()).isEqualTo(8);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void softDeleteExcludesItFromFindByUserAndTitle() {
        int userId = userRepository.insert("reviewer3@example.com", "hash", "Reviewer", Role.USER).id();
        var review = repository.insert(userId, 100, 5, "Meh");

        repository.softDelete(review.id(), review.version());

        assertThat(repository.findByUserAndTitle(userId, 100)).isEmpty();
    }

    @Test
    void aggregateForTitleAveragesAcrossReviewers() {
        int user1 = userRepository.insert("reviewer4@example.com", "hash", "R4", Role.USER).id();
        int user2 = userRepository.insert("reviewer5@example.com", "hash", "R5", Role.USER).id();
        repository.insert(user1, 200, 10, null);
        repository.insert(user2, 200, 6, null);

        var aggregate = repository.aggregateForTitle(200);

        assertThat(aggregate.count()).isEqualTo(2);
        assertThat(aggregate.average()).isCloseTo(8.0, within(0.01));
    }

    @Test
    void aggregateForTitleIsZeroWhenNoReviewsExist() {
        var aggregate = repository.aggregateForTitle(999999);

        assertThat(aggregate.count()).isEqualTo(0);
        assertThat(aggregate.average()).isEqualTo(0.0);
    }
}
