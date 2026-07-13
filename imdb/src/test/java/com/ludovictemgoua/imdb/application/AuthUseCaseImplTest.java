package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthUseCaseImplTest {

    @Mock
    UserRepository userRepository;
    @Mock
    JwtService jwtService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void registerCreatesAUserWithRoleUser() {
        given(userRepository.existsByEmail("ada@example.com")).willReturn(false);
        given(userRepository.insert(any(), any(), any(), any()))
                .willReturn(new User(1, "ada@example.com", "hash", "Ada", null, Role.USER, 0));
        given(jwtService.issueAccessToken(1, Set.of(Role.USER))).willReturn("access");
        given(jwtService.issueRefreshToken(1)).willReturn("refresh");

        var tokens = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder)
                .register(new RegisterRequest("ada@example.com", "password123", "Ada"));

        assertThat(tokens.accessToken()).isEqualTo("access");
        assertThat(tokens.refreshToken()).isEqualTo("refresh");
        verify(userRepository).insert(eq("ada@example.com"), anyString(), eq("Ada"), eq(Role.USER));
    }

    @Test
    void registerThrowsConflictWhenEmailAlreadyExists() {
        given(userRepository.existsByEmail("ada@example.com")).willReturn(true);
        var useCase = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder);

        assertThatThrownBy(() -> useCase.register(new RegisterRequest("ada@example.com", "pw", "Ada")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void loginIssuesTokensForACorrectPassword() {
        String hash = passwordEncoder.encode("password123");
        given(userRepository.findByEmail("ada@example.com"))
                .willReturn(Optional.of(new User(1, "ada@example.com", hash, "Ada", null, Role.USER, 0)));
        given(jwtService.issueAccessToken(1, Set.of(Role.USER))).willReturn("access");
        given(jwtService.issueRefreshToken(1)).willReturn("refresh");

        var tokens = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder)
                .login(new LoginRequest("ada@example.com", "password123"));

        assertThat(tokens.accessToken()).isEqualTo("access");
    }

    @Test
    void loginThrowsForbiddenForAWrongPassword() {
        String hash = passwordEncoder.encode("password123");
        given(userRepository.findByEmail("ada@example.com"))
                .willReturn(Optional.of(new User(1, "ada@example.com", hash, "Ada", null, Role.USER, 0)));
        var useCase = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder);

        assertThatThrownBy(() -> useCase.login(new LoginRequest("ada@example.com", "wrong")))
                .isInstanceOf(ForbiddenException.class);
    }
}
