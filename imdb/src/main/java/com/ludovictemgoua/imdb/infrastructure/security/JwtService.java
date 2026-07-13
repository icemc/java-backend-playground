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
            return Optional.of(new Parsed(userId, roles));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public record Parsed(int userId, Set<Role> roles) {
    }
}
