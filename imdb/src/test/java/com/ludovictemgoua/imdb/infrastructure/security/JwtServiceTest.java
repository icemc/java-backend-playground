package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private final JwtService jwtService = new JwtService(
            "test-secret-at-least-32-bytes-long-for-hs256", Duration.ofMinutes(15), Duration.ofDays(7));

    @Test
    void issuedAccessTokenParsesBackToTheSameUserAndRoles() {
        String token = jwtService.issueAccessToken(42, Set.of(Role.USER, Role.ADMIN));

        var parsed = jwtService.parse(token).orElseThrow();

        assertThat(parsed.userId()).isEqualTo(42);
        assertThat(parsed.roles()).containsExactlyInAnyOrder(Role.USER, Role.ADMIN);
    }

    @Test
    void parseReturnsEmptyForATamperedToken() {
        // Flipping only the very last character isn't reliable - a base64url signature's final
        // character often encodes fewer than 6 significant bits (the rest is decoder-ignored
        // padding), so some single-character swaps at that exact position decode to the same bytes
        // and the signature still verifies. Replacing the last 10 characters guarantees an actual
        // content change deep enough into the signature to invalidate it.
        String token = jwtService.issueAccessToken(1, Set.of(Role.USER));
        String tampered = token.substring(0, token.length() - 10) + "XXXXXXXXXX";

        assertThat(jwtService.parse(tampered)).isEmpty();
    }

    @Test
    void parseReturnsEmptyForAnAlreadyExpiredToken() {
        var shortLived = new JwtService(
                "test-secret-at-least-32-bytes-long-for-hs256", Duration.ofMillis(1), Duration.ofDays(7));
        String token = shortLived.issueAccessToken(1, Set.of(Role.USER));

        await(50);

        assertThat(shortLived.parse(token)).isEmpty();
    }

    private static void await(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
