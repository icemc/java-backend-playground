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

    @Test
    void refreshIssuesNewTokensForAValidRefreshToken() {
        given(jwtService.parse("refresh-token"))
                .willReturn(Optional.of(new JwtService.Parsed(1, Set.of(), true)));
        given(userRepository.findById(1))
                .willReturn(Optional.of(new User(1, "ada@example.com", "hash", "Ada", null, Role.USER, 0)));
        given(jwtService.issueAccessToken(1, Set.of(Role.USER))).willReturn("new-access");
        given(jwtService.issueRefreshToken(1)).willReturn("new-refresh");

        var tokens = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder)
                .refresh("refresh-token");

        assertThat(tokens.accessToken()).isEqualTo("new-access");
        assertThat(tokens.refreshToken()).isEqualTo("new-refresh");
    }

    // Regression test for a real bug found by Copilot code review: an access token used to parse
    // successfully here too (JwtService.parse doesn't care which kind of token it's given), so a
    // caller could mint a fresh token pair from their own short-lived access token without ever
    // holding a real refresh token, defeating the point of the access token's short TTL.
    @Test
    void refreshRejectsAnAccessTokenPresentedAsARefreshToken() {
        given(jwtService.parse("access-token"))
                .willReturn(Optional.of(new JwtService.Parsed(1, Set.of(Role.USER), false)));
        var useCase = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder);

        assertThatThrownBy(() -> useCase.refresh("access-token"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void refreshThrowsForbiddenForAnUnparsableToken() {
        given(jwtService.parse("garbage")).willReturn(Optional.empty());
        var useCase = new AuthUseCaseImpl(userRepository, jwtService, passwordEncoder);

        assertThatThrownBy(() -> useCase.refresh("garbage"))
                .isInstanceOf(ForbiddenException.class);
    }
}
