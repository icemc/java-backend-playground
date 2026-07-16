package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "imdb.bootstrap-admin.email=admin@imdb.local",
        "imdb.bootstrap-admin.password=change-me-please"
})
class BootstrapAdminRunnerIntegrationTest {

    @Autowired
    UserRepository userRepository;

    @Test
    void bootstrapAdminExistsWithAdminRoleAfterStartup() {
        var admin = userRepository.findByEmail("admin@imdb.local").orElseThrow();

        assertThat(admin.role()).isEqualTo(Role.ADMIN);
    }
}
