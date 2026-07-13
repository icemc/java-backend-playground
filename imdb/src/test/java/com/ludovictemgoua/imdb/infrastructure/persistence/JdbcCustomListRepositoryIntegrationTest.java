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
class JdbcCustomListRepositoryIntegrationTest {

    @Autowired
    JdbcCustomListRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void insertThenFindByIdRoundTrips() {
        int userId = userRepository.insert("lister1@example.com", "hash", "Lister", Role.USER).id();

        var created = repository.insert(userId, "Best of 2024", Visibility.PRIVATE);

        var found = repository.findById(created.id()).orElseThrow();
        assertThat(found.name()).isEqualTo("Best of 2024");
        assertThat(found.items()).isEmpty();
    }

    @Test
    void addItemThenFindByIdIncludesIt() {
        int userId = userRepository.insert("lister2@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Watch Later", Visibility.PUBLIC);

        repository.addItem(created.id(), 100);

        assertThat(repository.findById(created.id()).orElseThrow().items())
                .extracting("titleId").contains("tt0000100");
    }

    @Test
    void removeItemExcludesItFromTheList() {
        int userId = userRepository.insert("lister3@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Watch Later", Visibility.PUBLIC);
        repository.addItem(created.id(), 100);

        repository.removeItem(created.id(), 100);

        assertThat(repository.findById(created.id()).orElseThrow().items()).isEmpty();
    }

    @Test
    void updateRenamesAndBumpsVersion() {
        int userId = userRepository.insert("lister4@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Old Name", Visibility.PRIVATE);

        var result = repository.update(created.id(), "New Name", Visibility.PUBLIC, created.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findById(created.id()).orElseThrow();
        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.visibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void softDeleteExcludesItFromFindById() {
        int userId = userRepository.insert("lister5@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Delete Me", Visibility.PRIVATE);

        repository.softDelete(created.id(), created.version());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void findPublicOnlyReturnsPublicLists() {
        int userId = userRepository.insert("lister6@example.com", "hash", "Lister", Role.USER).id();
        repository.insert(userId, "Public List", Visibility.PUBLIC);
        repository.insert(userId, "Private List", Visibility.PRIVATE);

        var publicLists = repository.findPublic(0, 20);

        assertThat(publicLists.content()).extracting("name").contains("Public List").doesNotContain("Private List");
    }
}
