package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class JdbcUserRepositoryIntegrationTest {

    @Autowired
    JdbcUserRepository repository;

    @Test
    void insertThenFindByEmailReturnsTheSameUser() {
        var inserted = repository.insert("ada@example.com", "hash1", "Ada", Role.USER);

        var found = repository.findByEmail("ada@example.com").orElseThrow();

        assertThat(found.id()).isEqualTo(inserted.id());
        assertThat(found.displayName()).isEqualTo("Ada");
        assertThat(found.role()).isEqualTo(Role.USER);
        assertThat(found.version()).isEqualTo(0);
    }

    @Test
    void existsByEmailIsFalseForAnUnknownAddress() {
        assertThat(repository.existsByEmail("nobody@example.com")).isFalse();
    }

    @Test
    void updateProfileBumpsVersionAndPersistsChanges() {
        var user = repository.insert("grace@example.com", "hash2", "Grace", Role.USER);

        var result = repository.updateProfile(user.id(), "Grace H.", "Compiler pioneer", user.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findById(user.id()).orElseThrow();
        assertThat(updated.displayName()).isEqualTo("Grace H.");
        assertThat(updated.bio()).isEqualTo("Compiler pioneer");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void updateProfileReturnsVersionConflictOnStaleVersion() {
        var user = repository.insert("alan@example.com", "hash3", "Alan", Role.USER);

        var result = repository.updateProfile(user.id(), "Alan T.", null, user.version() + 1);

        assertThat(result).isEqualTo(WriteResult.VERSION_CONFLICT);
    }

    @Test
    void softDeleteExcludesTheUserFromFindById() {
        var user = repository.insert("delete-me@example.com", "hash4", "Temp", Role.USER);

        repository.softDelete(user.id());

        assertThat(repository.findById(user.id())).isEmpty();
    }
}
