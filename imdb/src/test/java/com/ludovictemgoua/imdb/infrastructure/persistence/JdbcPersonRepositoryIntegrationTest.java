package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
}
