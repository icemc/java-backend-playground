package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public BootstrapAdminRunner(
            UserRepository userRepository, PasswordEncoder passwordEncoder,
            @Value("${imdb.bootstrap-admin.email}") String bootstrapEmail,
            @Value("${imdb.bootstrap-admin.password}") String bootstrapPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = bootstrapEmail;
        this.bootstrapPassword = bootstrapPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(bootstrapEmail) || !StringUtils.hasText(bootstrapPassword)) {
            log.debug("no bootstrap admin configured (imdb.bootstrap-admin.email/password unset)");
            return;
        }
        if (userRepository.existsByEmail(bootstrapEmail)) {
            log.debug("bootstrap admin already exists: email={}", bootstrapEmail);
            return;
        }
        userRepository.insert(bootstrapEmail, passwordEncoder.encode(bootstrapPassword), "Admin", Role.ADMIN);
        log.info("bootstrap admin created: email={}", bootstrapEmail);
    }
}
