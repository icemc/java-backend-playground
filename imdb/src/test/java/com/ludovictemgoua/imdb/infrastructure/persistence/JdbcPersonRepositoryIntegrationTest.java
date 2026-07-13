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
class JdbcPersonRepositoryIntegrationTest {

    @Autowired
    JdbcPersonRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void findByNameReturnsBothPeopleSharingAName() {
        var candidates = repository.findByName("Jamie Lee");

        assertThat(candidates).extracting("id").containsExactlyInAnyOrder("nm0000020", "nm0000021");
    }

    @Test
    void findByNameReturnsExactlyOneMatchForAUniqueName() {
        var candidates = repository.findByName("Kevin Bacon");

        assertThat(candidates).extracting("id").containsExactly("nm0000001");
    }

    @Test
    void findNameByIdResolvesAKnownPerson() {
        assertThat(repository.findNameById(1)).contains("Kevin Bacon");
    }

    @Test
    void findNameByIdIsEmptyForAnUnknownPerson() {
        assertThat(repository.findNameById(987654)).isEmpty();
    }

    @Test
    void findNamesByIdsBatchResolvesMultiplePeople() {
        var names = repository.findNamesByIds(List.of(1, 6, 987654));

        assertThat(names).containsEntry(1, "Kevin Bacon").containsEntry(6, "Tom Hanks");
        assertThat(names).doesNotContainKey(987654);
    }

    @Test
    void findByNameExcludesASoftDeletedPerson() {
        jdbc.update("UPDATE name_basics SET deleted_at = now() WHERE nconst = 1");

        assertThat(repository.findByName("Kevin Bacon")).isEmpty();
    }

    @Test
    void insertPersonThenFindCoreRoundTrips() {
        var created = repository.insertPerson("Ada Lovelace", 1815, 1852, List.of("mathematician"));

        var found = repository.findCore(ImdbIds.parsePersonId(created.id())).orElseThrow();

        assertThat(found.primaryName()).isEqualTo("Ada Lovelace");
        assertThat(found.version()).isEqualTo(0);
    }

    @Test
    void insertedPersonIdIsAboveTheSeededRange() {
        var created = repository.insertPerson("New Person", null, null, List.of());

        assertThat(ImdbIds.parsePersonId(created.id())).isGreaterThan(10);
    }

    @Test
    void updatePersonBumpsVersionAndPersists() {
        var created = repository.insertPerson("Old Name", null, null, List.of());
        int nconst = ImdbIds.parsePersonId(created.id());

        var result = repository.updatePerson(nconst, "New Name", 1990, null, List.of("actor"), created.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        assertThat(repository.findCore(nconst).orElseThrow().primaryName()).isEqualTo("New Name");
    }

    @Test
    void updatePersonReturnsVersionConflictOnStaleVersion() {
        var created = repository.insertPerson("Stale", null, null, List.of());
        int nconst = ImdbIds.parsePersonId(created.id());

        var result = repository.updatePerson(nconst, "New Name", null, null, List.of(), created.version() + 1);

        assertThat(result).isEqualTo(WriteResult.VERSION_CONFLICT);
    }

    @Test
    void softDeletePersonExcludesThemFromFindCore() {
        var created = repository.insertPerson("Delete Me", null, null, List.of());
        int nconst = ImdbIds.parsePersonId(created.id());

        repository.softDeletePerson(nconst);

        assertThat(repository.findCore(nconst)).isEmpty();
    }
}
