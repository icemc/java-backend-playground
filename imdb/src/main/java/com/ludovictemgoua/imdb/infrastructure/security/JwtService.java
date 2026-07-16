package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class JwtService {

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtService(
            @Value("${imdb.jwt.secret}") String secret,
            @Value("${imdb.jwt.access-token-ttl:PT15M}") Duration accessTokenTtl,
            @Value("${imdb.jwt.refresh-token-ttl:P7D}") Duration refreshTokenTtl) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
    }

    public String issueAccessToken(int userId, Set<Role> roles) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", roles.stream().map(Role::name).collect(Collectors.toList()))
                .claim("type", "access")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                .signWith(key)
                .compact();
    }

    public String issueRefreshToken(int userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("type", "refresh")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenTtl)))
                .signWith(key)
                .compact();
    }

    public Optional<Parsed> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            int userId = Integer.parseInt(claims.getSubject());
            @SuppressWarnings("unchecked")
            List<String> roleNames = claims.get("roles", List.class);
            Set<Role> roles = roleNames == null ? Set.of()
                    : roleNames.stream().map(Role::valueOf).collect(Collectors.toSet());
            // "refresh" is the only value that ever means anything here - a bare/missing type claim
            // is treated as an access token (not just refresh tokens predating this claim, but also
            // any already-issued, not-yet-expired access token from before this check existed, so a
            // rolling deploy doesn't force every logged-in session to re-authenticate).
            boolean refreshToken = "refresh".equals(claims.get("type", String.class));
            return Optional.of(new Parsed(userId, roles, refreshToken));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    // refreshToken distinguishes a redeemable-once-for-new-tokens credential from a bearer-auth
    // credential - without checking it, a refresh token satisfies JwtAuthenticationFilter just like
    // an access token would (it parses and signs the same way, and simply carries no roles), and an
    // access token satisfies AuthUseCaseImpl.refresh() just like a refresh token would (findById
    // succeeds for its userId regardless of which token type produced it). Found by Copilot code
    // review, verified against this exact codebase before fixing: JwtAuthenticationFilter's roles
    // check alone happened to be a correct-by-coincidence fix given every user always has exactly
    // one role, but didn't close the second, symmetric hole at the refresh endpoint - this claim is
    // the actual signal both call sites should have been checking.
    public record Parsed(int userId, Set<Role> roles, boolean refreshToken) {
    }
}
