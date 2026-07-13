package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
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
class JdbcWatchlistRepositoryIntegrationTest {

    @Autowired
    JdbcWatchlistRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void findOrCreateByUserIdCreatesAnEmptyPrivateWatchlistOnFirstAccess() {
        int userId = userRepository.insert("watchlist-user@example.com", "hash", "User", Role.USER).id();

        var watchlist = repository.findOrCreateByUserId(userId);

        assertThat(watchlist.userId()).isEqualTo(userId);
        assertThat(watchlist.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(watchlist.items()).isEmpty();
    }

    @Test
    void findOrCreateByUserIdIsIdempotent() {
        int userId = userRepository.insert("watchlist-user2@example.com", "hash", "User", Role.USER).id();

        var first = repository.findOrCreateByUserId(userId);
        var second = repository.findOrCreateByUserId(userId);

        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test
    void addItemThenFindOrCreateIncludesIt() {
        int userId = userRepository.insert("watchlist-user3@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);

        repository.addItem(watchlist.id(), 100);

        assertThat(repository.findOrCreateByUserId(userId).items()).extracting("titleId").contains("tt0000100");
    }

    @Test
    void removeItemExcludesItFromTheWatchlist() {
        int userId = userRepository.insert("watchlist-user4@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);
        repository.addItem(watchlist.id(), 100);

        repository.removeItem(watchlist.id(), 100);

        assertThat(repository.findOrCreateByUserId(userId).items()).isEmpty();
    }

    @Test
    void updateVisibilityChangesItAndBumpsVersion() {
        int userId = userRepository.insert("watchlist-user5@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);

        var result = repository.updateVisibility(watchlist.id(), Visibility.PUBLIC, watchlist.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        assertThat(repository.findByUserId(userId).orElseThrow().visibility()).isEqualTo(Visibility.PUBLIC);
    }
}
