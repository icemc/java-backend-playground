# IMDb CRUD Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the read-only `imdb` API into a full CRUD RESTful API: JWT-based auth, a user-generated content layer (watchlists/reviews/custom lists), and admin CRUD over the core IMDb entities (titles/people/ratings/cast-crew), plus the optimistic locking, cache invalidation, and soft-delete conventions that make those writes safe.

**Architecture:** Strict addition on top of the existing onion layers (`presentation -> infrastructure -> application -> domain`, plus `utils`). New resources get the same interface/impl/decorator shape already used by the four read endpoints. Admin writes land in the existing `TitleController`/`PersonController`/`GenreController` classes, gated by `@PreAuthorize`, not new `Admin*` classes.

**Tech Stack:** Spring Boot 4.1, Java 21, Maven, `spring-boot-starter-security` (new), plain JDBC (`NamedParameterJdbcTemplate`), PostgreSQL, Redis (Spring Cache), Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers, Postman/Newman.

## Global Constraints

- Package root: `com.ludovictemgoua.imdb`. Follow the onion layering exactly: `domain` imports nothing; `application` imports only `domain`/`utils`; `infrastructure`/`presentation` may import inward layers only.
- Every writable table gets `version INTEGER NOT NULL DEFAULT 0` and `deleted_at TIMESTAMPTZ` (soft delete, per `docs/crud-expansion-design.md` §6.1/§6.4). Every read query filters `WHERE deleted_at IS NULL`.
- IDs at the API boundary are always IMDb-style `tt`/`nm` strings via `utils.ImdbIds`, never raw integers - matches the existing convention exactly.
- New Flyway migrations start at `V5` (current latest is `V4__title_principals_nconst_index.sql`).
- Domain models are Java records. Repositories are interfaces in `domain.repository`, implementations in `infrastructure.persistence` using `NamedParameterJdbcTemplate` + `MapSqlParameterSource`, row-mapped via static private methods - copy `JdbcTitleRepository`'s style exactly.
- Unit tests: JUnit 5 + `@ExtendWith(MockitoExtension.class)` + AssertJ, no Spring context, mocked repository interfaces - copy `TitleSearchUseCaseImplTest`'s style exactly.
- Integration tests: `@Import(TestcontainersConfiguration.class) @SpringBootTest @Transactional @Sql("/fixtures/fixture-data.sql")`, named `*IntegrationTest.java` (Surefire/Failsafe split depends on this suffix - see `pom.xml`).
- Controller tests: `@WebMvcTest(XController.class)` + `@MockitoBean` on the use-case interfaces - copy `TitleControllerTest`'s style exactly.
- New domain exceptions (`ConflictException`, `ForbiddenException`) get handlers added to the existing `ApiExceptionHandler` (which `extends ResponseEntityExceptionHandler` - do not replace this with a bare `@RestControllerAdvice`, see that class's own header comment for why).
- Run `JAVA_HOME="/c/Program Files/Java/jdk-21"` before any `./mvnw` command on this machine (sdkman's default `mvnw` resolves Java 8 otherwise).

---

## Phase 1: Security & Auth Foundation

### Task 1.1: `users` table, `User`/`Role` domain models, `UserRepository`

**Files:**
- Create: `src/main/resources/db/migration/V5__users_table.sql`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/Role.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/User.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/repository/UserRepository.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcUserRepository.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcUserRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `User(int id, String email, String passwordHash, String displayName, String bio, Role role, int version)`, `Role.USER`/`Role.ADMIN`, `UserRepository` with `insert`, `findById`, `findByEmail`, `existsByEmail`, `updateProfile`, `updateRole`, `softDelete`. `WriteResult` enum (`SUCCESS`, `NOT_FOUND`, `VERSION_CONFLICT`) used by every later versioned-update repository method in this plan.

- [ ] **Step 1: Write the failing integration test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcUserRepositoryIntegrationTest`
Expected: FAIL - compilation error, `JdbcUserRepository`/`Role`/`WriteResult` don't exist yet.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE users (
    id            SERIAL PRIMARY KEY,
    email         TEXT NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT NOT NULL,
    bio           TEXT,
    role          TEXT NOT NULL DEFAULT 'USER',
    version       INTEGER NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_users_email ON users (email) WHERE deleted_at IS NULL;
```

- [ ] **Step 4: Create `Role`, `User`, `WriteResult`**

```java
package com.ludovictemgoua.imdb.domain.model;

public enum Role { USER, ADMIN }
```

```java
package com.ludovictemgoua.imdb.domain.model;

public record User(int id, String email, String passwordHash, String displayName, String bio,
                    Role role, int version) {
}
```

```java
package com.ludovictemgoua.imdb.domain.repository;

// Shared by every repository method backing a PUT/PATCH update or a DELETE on a versioned entity
// (users, titles, people, reviews, lists, ...) - lets the use-case layer distinguish "no such row"
// from "row exists but your version is stale" without the repository itself deciding which HTTP
// status or domain exception that becomes (that stays an application-layer decision, matching how
// NotFoundException is already thrown by use cases today, not repositories).
public enum WriteResult { SUCCESS, NOT_FOUND, VERSION_CONFLICT }
```

- [ ] **Step 5: Create `UserRepository` and `JdbcUserRepository`**

```java
package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;

import java.util.Optional;

public interface UserRepository {

    User insert(String email, String passwordHash, String displayName, Role role);

    Optional<User> findById(int id);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    WriteResult updateProfile(int id, String displayName, String bio, int expectedVersion);

    void updateRole(int id, Role role);

    void softDelete(int id);

    PagedResult<User> findAll(int page, int size);
}
```

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcUserRepository implements UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User insert(String email, String passwordHash, String displayName, Role role) {
        String sql = """
                INSERT INTO users (email, password_hash, display_name, role)
                VALUES (:email, :passwordHash, :displayName, :role)
                """;
        var params = new MapSqlParameterSource()
                .addValue("email", email).addValue("passwordHash", passwordHash)
                .addValue("displayName", displayName).addValue("role", role.name());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        int id = keyHolder.getKey().intValue();
        return new User(id, email, passwordHash, displayName, null, role, 0);
    }

    @Override
    public Optional<User> findById(int id) {
        String sql = "SELECT * FROM users WHERE id = :id AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("id", id), JdbcUserRepository::mapUser).stream().findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String sql = "SELECT * FROM users WHERE email = :email AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("email", email), JdbcUserRepository::mapUser).stream().findFirst();
    }

    @Override
    public boolean existsByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = :email AND deleted_at IS NULL",
                Map.of("email", email), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public WriteResult updateProfile(int id, String displayName, String bio, int expectedVersion) {
        if (findById(id).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                UPDATE users SET display_name = :displayName, bio = :bio, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("displayName", displayName).addValue("bio", bio)
                .addValue("id", id).addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(sql, params);
        return updated == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public void updateRole(int id, Role role) {
        jdbc.update("UPDATE users SET role = :role, version = version + 1 WHERE id = :id",
                new MapSqlParameterSource().addValue("role", role.name()).addValue("id", id));
    }

    @Override
    public void softDelete(int id) {
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = :id", Map.of("id", id));
    }

    @Override
    public PagedResult<User> findAll(int page, int size) {
        String dataSql = "SELECT * FROM users WHERE deleted_at IS NULL ORDER BY id LIMIT :limit OFFSET :offset";
        String countSql = "SELECT count(*) FROM users WHERE deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        List<User> content = jdbc.query(dataSql, params, JdbcUserRepository::mapUser);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    private static User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(rs.getInt("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("bio"),
                Role.valueOf(rs.getString("role")), rs.getInt("version"));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcUserRepositoryIntegrationTest`
Expected: PASS, 5 tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__users_table.sql src/main/java/com/ludovictemgoua/imdb/domain/model/Role.java src/main/java/com/ludovictemgoua/imdb/domain/model/User.java src/main/java/com/ludovictemgoua/imdb/domain/repository/UserRepository.java src/main/java/com/ludovictemgoua/imdb/domain/repository/WriteResult.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcUserRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcUserRepositoryIntegrationTest.java
git commit -m "Add users table, User/Role domain models, and JdbcUserRepository"
```

### Task 1.2: Spring Security dependency, `PasswordEncoder`, `JwtService`

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/JwtService.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/security/JwtServiceTest.java`

**Interfaces:**
- Consumes: `Role` (Task 1.1)
- Produces: `JwtService.issueAccessToken(int userId, Set<Role> roles)`, `issueRefreshToken(int userId)`,
  `JwtService.Parsed(int userId, Set<Role> roles)` record, `parse(String token)` returning
  `Optional<JwtService.Parsed>` (empty on expired/tampered/malformed tokens - never throws).

- [ ] **Step 1: Write the failing unit test**

```java
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
        String token = jwtService.issueAccessToken(1, Set.of(Role.USER));
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");

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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=JwtServiceTest`
Expected: FAIL - `JwtService` doesn't exist yet.

- [ ] **Step 3: Add the Spring Security and JJWT dependencies to `pom.xml`**

Add inside `<dependencies>`, alongside the existing entries:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 4: Create `JwtService`**

```java
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
```

- [ ] **Step 5: Add `imdb.jwt.secret` to `application.yaml` and the `PasswordEncoder` bean**

Append to `src/main/resources/application.yaml`:

```yaml
imdb:
  jwt:
    # No default - must be set via JWT_SECRET env var (>= 32 bytes for HS256). Deliberately absent
    # here rather than defaulted to something committed, since this signs every access/refresh token.
    secret: ${JWT_SECRET}
```

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=JwtServiceTest`
Expected: PASS, 3 tests green.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/resources/application.yaml src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java src/main/java/com/ludovictemgoua/imdb/infrastructure/security/JwtService.java src/test/java/com/ludovictemgoua/imdb/infrastructure/security/JwtServiceTest.java
git commit -m "Add Spring Security dependency, PasswordEncoder, and JwtService"
```

### Task 1.3: `JwtAuthenticationFilter`, security filter chain, `ProblemDetail` error handlers

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/JwtAuthenticationFilter.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/ProblemDetailAuthenticationEntryPoint.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/ProblemDetailAccessDeniedHandler.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/security/JwtAuthenticationFilterTest.java`

**Interfaces:**
- Consumes: `JwtService.parse(String)` (Task 1.2)
- Produces: a populated `SecurityContextHolder` (authorities `ROLE_USER`/`ROLE_ADMIN`) for any request
  carrying a valid `Authorization: Bearer` token; the filter chain permits `/api/v1/auth/**`, GETs on the
  existing read endpoints plus the new public browse endpoints, and `/actuator/**` with no token at all;
  everything else requires authentication. Role-specific gating is `@PreAuthorize` on individual methods
  (later tasks); ownership gating (e.g. "is this your list") is a use-case-level check, not done here.

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    JwtService jwtService;
    @Mock
    HttpServletRequest request;
    @Mock
    HttpServletResponse response;
    @Mock
    FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void populatesSecurityContextForAValidBearerToken() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer good-token");
        given(jwtService.parse("good-token"))
                .willReturn(Optional.of(new JwtService.Parsed(7, Set.of(Role.USER))));

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getName()).isEqualTo("7");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyWithNoAuthorizationHeader() throws Exception {
        given(request.getHeader("Authorization")).willReturn(null);

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyForAnInvalidToken() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer bad-token");
        given(jwtService.parse("bad-token")).willReturn(Optional.empty());

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=JwtAuthenticationFilterTest`
Expected: FAIL - `JwtAuthenticationFilter` doesn't exist yet.

- [ ] **Step 3: Create `JwtAuthenticationFilter`**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            jwtService.parse(token).ifPresent(parsed -> authenticate(parsed.userId(), parsed.roles()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(int userId, Set<Role> roles) {
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
```

- [ ] **Step 4: Create the `ProblemDetail` entry point and access-denied handler**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

// Spring Security's default 401 response isn't a ProblemDetail - it's a bare, framework-shaped
// response, inconsistent with every other error this API returns (ApiExceptionHandler). This keeps
// the shape consistent regardless of whether Spring MVC or Spring Security rejected the request.
@Component
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public ProblemDetailAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "A valid Authorization: Bearer token is required");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public ProblemDetailAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
```

- [ ] **Step 5: Wire the filter chain into `SecurityConfig`**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
            ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
            ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/api/v1/auth/**").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/titles/**", "/api/v1/genres/**", "/api/v1/people/six-degrees",
                                "/api/v1/lists/public", "/api/v1/lists/*", "/api/v1/users/*",
                                "/api/v1/users/*/watchlist", "/api/v1/users/*/reviews").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=JwtAuthenticationFilterTest`
Expected: PASS, 3 tests green.

- [ ] **Step 7: Run the full unit suite to confirm nothing else broke**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test`
Expected: PASS - adding Spring Security can make existing `@WebMvcTest`s 401/403 if a test doesn't
authenticate; if any of the four existing controller tests fail here, add
`.with(SecurityMockMvcRequestPostProcessors.anonymous())` or confirm the failing path is already in the
`permitAll()` list above before proceeding - do not weaken the filter chain to make a test pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/infrastructure/security/
git add src/test/java/com/ludovictemgoua/imdb/infrastructure/security/JwtAuthenticationFilterTest.java
git commit -m "Add JwtAuthenticationFilter, security filter chain, and ProblemDetail error handlers"
```

### Task 1.4: `ConflictException`/`ForbiddenException`, `AuthUseCase`, `AuthController`

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/exception/ConflictException.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/exception/ForbiddenException.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/presentation/ApiExceptionHandler.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/AuthUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/AuthUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/RegisterRequest.java`, `LoginRequest.java`, `TokenPair.java` (records)
- Create: `src/main/java/com/ludovictemgoua/imdb/presentation/AuthController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/AuthUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/AuthControllerTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 1.1), `JwtService` (Task 1.2), `PasswordEncoder` (Task 1.2)
- Produces: `AuthUseCase.register(RegisterRequest)`, `login(LoginRequest)`, `refresh(String refreshToken)`,
  all returning `TokenPair(String accessToken, String refreshToken)`. `ConflictException`/
  `ForbiddenException` (both `RuntimeException`), used by every later task needing 409/403.

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
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
        verify(userRepository).insert("ada@example.com", any(), "Ada", Role.USER);
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
                .isInstanceOf(com.ludovictemgoua.imdb.domain.exception.ForbiddenException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=AuthUseCaseImplTest`
Expected: FAIL - `AuthUseCaseImpl`/`RegisterRequest`/`LoginRequest`/`ConflictException`/`ForbiddenException` don't exist yet.

- [ ] **Step 3: Create the two new domain exceptions**

```java
package com.ludovictemgoua.imdb.domain.exception;

public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
```

```java
package com.ludovictemgoua.imdb.domain.exception;

public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 4: Add handlers to `ApiExceptionHandler`**

Add these two methods to the existing class, alongside `handleNotFound`/`handleBadId` (before the
catch-all `handleUnexpected`):

```java
    @ExceptionHandler(com.ludovictemgoua.imdb.domain.exception.ConflictException.class)
    public ProblemDetail handleConflict(com.ludovictemgoua.imdb.domain.exception.ConflictException ex) {
        log.debug("conflict: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(com.ludovictemgoua.imdb.domain.exception.ForbiddenException.class)
    public ProblemDetail handleForbidden(com.ludovictemgoua.imdb.domain.exception.ForbiddenException ex) {
        log.debug("forbidden: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
    }
```

(Use the fully-qualified names as shown, or add proper `import` statements at the top of the file next to
the existing `NotFoundException` import - either is fine, match whichever style is already there.)

- [ ] **Step 5: Create the request/response records and `AuthUseCase`/`AuthUseCaseImpl`**

```java
package com.ludovictemgoua.imdb.application;

public record RegisterRequest(String email, String password, String displayName) {
}
```

```java
package com.ludovictemgoua.imdb.application;

public record LoginRequest(String email, String password) {
}
```

```java
package com.ludovictemgoua.imdb.application;

public record TokenPair(String accessToken, String refreshToken) {
}
```

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.LoginRequest;
import com.ludovictemgoua.imdb.application.RegisterRequest;
import com.ludovictemgoua.imdb.application.TokenPair;

public interface AuthUseCase {

    TokenPair register(RegisterRequest request);

    TokenPair login(LoginRequest request);

    TokenPair refresh(String refreshToken);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.infrastructure.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class AuthUseCaseImpl implements AuthUseCase {

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthUseCaseImpl(UserRepository userRepository, JwtService jwtService, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public TokenPair register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }
        String hash = passwordEncoder.encode(request.password());
        User user = userRepository.insert(request.email(), hash, request.displayName(), Role.USER);
        return issueTokens(user);
    }

    @Override
    public TokenPair login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new ForbiddenException("Invalid email or password"));
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new ForbiddenException("Invalid email or password");
        }
        return issueTokens(user);
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        var parsed = jwtService.parse(refreshToken)
                .orElseThrow(() -> new ForbiddenException("Invalid or expired refresh token"));
        User user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> new ForbiddenException("Invalid or expired refresh token"));
        return issueTokens(user);
    }

    private TokenPair issueTokens(User user) {
        String access = jwtService.issueAccessToken(user.id(), Set.of(user.role()));
        String refresh = jwtService.issueRefreshToken(user.id());
        return new TokenPair(access, refresh);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=AuthUseCaseImplTest`
Expected: PASS, 4 tests green.

- [ ] **Step 7: Write the failing controller test**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.LoginRequest;
import com.ludovictemgoua.imdb.application.RegisterRequest;
import com.ludovictemgoua.imdb.application.TokenPair;
import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@WithAnonymousUser
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    AuthUseCase authUseCase;

    @Test
    void registerReturns201WithTokens() throws Exception {
        given(authUseCase.register(new RegisterRequest("ada@example.com", "password123", "Ada")))
                .willReturn(new TokenPair("access", "refresh"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("ada@example.com", "password123", "Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    void registerReturns409ForADuplicateEmail() throws Exception {
        given(authUseCase.register(new RegisterRequest("ada@example.com", "password123", "Ada")))
                .willThrow(new ConflictException("An account with this email already exists"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("ada@example.com", "password123", "Ada"))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginReturnsTokensForValidCredentials() throws Exception {
        given(authUseCase.login(new LoginRequest("ada@example.com", "password123")))
                .willReturn(new TokenPair("access", "refresh"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ada@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }
}
```

- [ ] **Step 8: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=AuthControllerTest`
Expected: FAIL - `AuthController` doesn't exist yet.

- [ ] **Step 9: Create `AuthController`**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.LoginRequest;
import com.ludovictemgoua.imdb.application.RegisterRequest;
import com.ludovictemgoua.imdb.application.TokenPair;
import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthUseCase authUseCase;

    public AuthController(AuthUseCase authUseCase) {
        this.authUseCase = authUseCase;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenPair register(@Valid @RequestBody RegisterRequest request) {
        return authUseCase.register(request);
    }

    @PostMapping("/login")
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authUseCase.login(request);
    }

    @PostMapping("/refresh")
    public TokenPair refresh(@RequestBody RefreshRequest request) {
        return authUseCase.refresh(request.refreshToken());
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
```

Add Bean Validation annotations to `RegisterRequest`/`LoginRequest` (both are plain records used as
`@RequestBody` types, so the annotations go directly on the record components):

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8) String password,
        @NotBlank String displayName) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}
```

- [ ] **Step 10: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=AuthControllerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/domain/exception/ConflictException.java src/main/java/com/ludovictemgoua/imdb/domain/exception/ForbiddenException.java src/main/java/com/ludovictemgoua/imdb/presentation/ApiExceptionHandler.java src/main/java/com/ludovictemgoua/imdb/application/ src/main/java/com/ludovictemgoua/imdb/presentation/AuthController.java src/test/java/com/ludovictemgoua/imdb/application/AuthUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/AuthControllerTest.java
git commit -m "Add ConflictException/ForbiddenException and the auth register/login/refresh flow"
```

### Task 1.5: `BootstrapAdminRunner`

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/BootstrapAdminRunner.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/security/BootstrapAdminRunnerIntegrationTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 1.1), `PasswordEncoder` (Task 1.2)
- Produces: on application startup, exactly one `ADMIN` user exists with the configured bootstrap email
  (idempotent - a restart never creates a second one).

- [ ] **Step 1: Write the failing integration test**

```java
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=BootstrapAdminRunnerIntegrationTest`
Expected: FAIL - no bootstrap admin is created yet.

- [ ] **Step 3: Add the bootstrap properties to `application.yaml`**

```yaml
imdb:
  bootstrap-admin:
    # No defaults for either - a fresh stack with neither set simply never gets an admin account,
    # which is the safe failure mode (an operator has to deliberately opt in), rather than shipping
    # a guessable default admin password.
    email: ${IMDB_BOOTSTRAP_ADMIN_EMAIL:}
    password: ${IMDB_BOOTSTRAP_ADMIN_PASSWORD:}
```

- [ ] **Step 4: Create `BootstrapAdminRunner`**

```java
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
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=BootstrapAdminRunnerIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Add the bootstrap env vars to `docker-compose.yaml`'s `imdb-service` environment block**

```yaml
      IMDB_BOOTSTRAP_ADMIN_EMAIL: "admin@imdb.local"
      IMDB_BOOTSTRAP_ADMIN_PASSWORD: "change-me-please"
      JWT_SECRET: "local-dev-only-secret-change-in-real-deployments-32bytes+"
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/infrastructure/security/BootstrapAdminRunner.java src/test/java/com/ludovictemgoua/imdb/infrastructure/security/BootstrapAdminRunnerIntegrationTest.java src/main/resources/application.yaml docker-compose.yaml
git commit -m "Add BootstrapAdminRunner so the ADMIN role is reachable on a fresh stack"
```

### Task 1.6: `CurrentUser` helper, `UserUseCase`, `UserController`

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/CurrentUser.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/UserProfile.java`, `PublicUserProfile.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/UpdateProfileRequest.java`, `RoleRequest.java` (records)
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/UserUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/UserUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/presentation/UserController.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/security/CurrentUserTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/UserUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/UserControllerTest.java`

**Interfaces:**
- Consumes: `UserRepository` (Task 1.1 - `findById`, `updateProfile`, `updateRole`, `softDelete`, `findAll`
  all already exist from that task)
- Produces: `CurrentUser.idOf(Authentication) -> Optional<Integer>`, `requireId(Authentication) -> int` -
  reused by every controller in Phases 6-8 needing "who is calling, if anyone" (this plan originally
  placed this helper in Phase 6; it's built here instead since `UserController` needs it first
  chronologically - Phase 6 Task 6.1 below has been corrected to consume it rather than create it).
  `UserProfile(int id, String email, String displayName, String bio, Role role, int version)` (excludes
  `passwordHash` - the API must never echo it back). `PublicUserProfile(int id, String displayName)`.
  `GET/PUT/DELETE /api/v1/users/me`, `GET /api/v1/users/{userId}`, `GET /api/v1/users` (admin),
  `PUT /api/v1/users/{userId}/role` (admin), `DELETE /api/v1/users/{userId}` (admin) - the remaining seven
  endpoints from `docs/crud-expansion-design.md` §4.1 not already covered by `AuthController` (Task 1.4).

- [ ] **Step 1: Write the failing `CurrentUser` unit test**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    @Test
    void idOfReturnsEmptyForNullAuthentication() {
        assertThat(CurrentUser.idOf(null)).isEmpty();
    }

    @Test
    void idOfReturnsTheParsedUserIdForARealToken() {
        var auth = new TestingAuthenticationToken("42", null);

        assertThat(CurrentUser.idOf(auth)).contains(42);
    }

    @Test
    void idOfReturnsEmptyForAnonymousAuthentication() {
        var auth = new TestingAuthenticationToken("anonymousUser", null);

        assertThat(CurrentUser.idOf(auth)).isEmpty();
    }

    @Test
    void requireIdThrowsWhenNoUserIsAuthenticated() {
        assertThatThrownBy(() -> CurrentUser.requireId(null)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=CurrentUserTest`
Expected: FAIL - `CurrentUser` doesn't exist yet.

- [ ] **Step 3: Create `CurrentUser`**

```java
package com.ludovictemgoua.imdb.infrastructure.security;

import org.springframework.security.core.Authentication;

import java.util.Optional;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<Integer> idOf(Authentication authentication) {
        if (authentication == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(authentication.getName()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static int requireId(Authentication authentication) {
        return idOf(authentication)
                .orElseThrow(() -> new IllegalStateException("No authenticated user in this request"));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=CurrentUserTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Write the failing `UserUseCaseImpl` unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class UserUseCaseImplTest {

    @Mock
    UserRepository userRepository;

    @Test
    void getOwnProfileExcludesThePasswordHash() {
        given(userRepository.findById(7))
                .willReturn(Optional.of(new User(7, "a@example.com", "secret-hash", "Ada", "bio", Role.USER, 0)));

        var profile = new UserUseCaseImpl(userRepository).getOwnProfile(7);

        assertThat(profile.email()).isEqualTo("a@example.com");
        assertThat(profile.displayName()).isEqualTo("Ada");
    }

    @Test
    void getOwnProfileThrowsNotFoundForAnUnknownId() {
        given(userRepository.findById(999)).willReturn(Optional.empty());

        assertThatThrownBy(() -> new UserUseCaseImpl(userRepository).getOwnProfile(999))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateOwnProfileThrowsConflictOnVersionMismatch() {
        given(userRepository.updateProfile(7, "New Name", "New Bio", 0)).willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new UserUseCaseImpl(userRepository);

        assertThatThrownBy(() -> useCase.updateOwnProfile(7, new UpdateProfileRequest("New Name", "New Bio", 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateRoleThrowsNotFoundForAnUnknownUser() {
        given(userRepository.findById(999)).willReturn(Optional.empty());
        var useCase = new UserUseCaseImpl(userRepository);

        assertThatThrownBy(() -> useCase.updateRole(999, Role.ADMIN)).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=UserUseCaseImplTest`
Expected: FAIL - `UserUseCaseImpl`/`UpdateProfileRequest` don't exist yet.

- [ ] **Step 7: Create the domain/request records and `UserUseCase`/`Impl`**

```java
package com.ludovictemgoua.imdb.domain.model;

public record UserProfile(int id, String email, String displayName, String bio, Role role, int version) {
}
```

```java
package com.ludovictemgoua.imdb.domain.model;

public record PublicUserProfile(int id, String displayName) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String displayName, String bio, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.validation.constraints.NotNull;

public record RoleRequest(@NotNull Role role) {
}
```

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.UpdateProfileRequest;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.UserProfile;

public interface UserUseCase {

    UserProfile getOwnProfile(int userId);

    UserProfile updateOwnProfile(int userId, UpdateProfileRequest request);

    void deleteOwnAccount(int userId);

    PublicUserProfile getPublicProfile(int userId);

    PagedResult<UserProfile> listAll(int page, int size);

    void updateRole(int userId, Role role);

    void deleteAccount(int userId);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.springframework.stereotype.Service;

@Service
public class UserUseCaseImpl implements UserUseCase {

    private final UserRepository userRepository;

    public UserUseCaseImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserProfile getOwnProfile(int userId) {
        return toProfile(findOrThrow(userId));
    }

    @Override
    public UserProfile updateOwnProfile(int userId, UpdateProfileRequest request) {
        WriteResult result = userRepository.updateProfile(userId, request.displayName(), request.bio(), request.version());
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No user with id " + userId);
            case VERSION_CONFLICT -> throw new ConflictException("Your profile was modified concurrently - refresh and retry");
            case SUCCESS -> { }
        }
        return getOwnProfile(userId);
    }

    @Override
    public void deleteOwnAccount(int userId) {
        userRepository.softDelete(userId);
    }

    @Override
    public PublicUserProfile getPublicProfile(int userId) {
        User user = findOrThrow(userId);
        return new PublicUserProfile(user.id(), user.displayName());
    }

    @Override
    public PagedResult<UserProfile> listAll(int page, int size) {
        PagedResult<User> users = userRepository.findAll(page, size);
        return new PagedResult<>(users.content().stream().map(UserUseCaseImpl::toProfile).toList(),
                users.totalElements(), users.page(), users.size());
    }

    @Override
    public void updateRole(int userId, Role role) {
        findOrThrow(userId);
        userRepository.updateRole(userId, role);
    }

    @Override
    public void deleteAccount(int userId) {
        findOrThrow(userId);
        userRepository.softDelete(userId);
    }

    private User findOrThrow(int userId) {
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("No user with id " + userId));
    }

    private static UserProfile toProfile(User user) {
        return new UserProfile(user.id(), user.email(), user.displayName(), user.bio(), user.role(), user.version());
    }
}
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=UserUseCaseImplTest`
Expected: PASS, 4 tests green.

- [ ] **Step 9: Write the failing `UserController` test**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    UserUseCase userUseCase;

    @Test
    void getOwnRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getOwnReturnsTheProfileForAnAuthenticatedUser() throws Exception {
        given(userUseCase.getOwnProfile(7))
                .willReturn(new UserProfile(7, "a@example.com", "Ada", "bio", Role.USER, 0));

        mockMvc.perform(get("/api/v1/users/me").with(SecurityMockMvcRequestPostProcessors.user("7").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada"));
    }

    @Test
    void getPublicProfileIsAccessibleAnonymously() throws Exception {
        given(userUseCase.getPublicProfile(7)).willReturn(new PublicUserProfile(7, "Ada"));

        mockMvc.perform(get("/api/v1/users/7")).andExpect(status().isOk());
    }

    @Test
    void deleteAccountRequiresAdminRole() throws Exception {
        mockMvc.perform(delete("/api/v1/users/7")
                        .with(SecurityMockMvcRequestPostProcessors.user("1").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 10: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=UserControllerTest`
Expected: FAIL - `UserController` doesn't exist yet.

- [ ] **Step 11: Create `UserController`**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.RoleRequest;
import com.ludovictemgoua.imdb.application.UpdateProfileRequest;
import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class UserController {

    private final UserUseCase userUseCase;

    public UserController(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
    }

    @GetMapping("/api/v1/users/me")
    public UserProfile getOwn(Authentication authentication) {
        return userUseCase.getOwnProfile(CurrentUser.requireId(authentication));
    }

    @PutMapping("/api/v1/users/me")
    public UserProfile updateOwn(Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        return userUseCase.updateOwnProfile(CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/api/v1/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwn(Authentication authentication) {
        userUseCase.deleteOwnAccount(CurrentUser.requireId(authentication));
    }

    @GetMapping("/api/v1/users/{userId}")
    public PublicUserProfile getPublicProfile(@PathVariable int userId) {
        return userUseCase.getPublicProfile(userId);
    }

    @GetMapping("/api/v1/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResult<UserProfile> listAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userUseCase.listAll(page, size);
    }

    @PutMapping("/api/v1/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public void updateRole(@PathVariable int userId, @Valid @RequestBody RoleRequest request) {
        userUseCase.updateRole(userId, request.role());
    }

    @DeleteMapping("/api/v1/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteAccount(@PathVariable int userId) {
        userUseCase.deleteAccount(userId);
    }
}
```

`GET /api/v1/users/{userId}` is already in the security filter chain's `permitAll()` GET list
(`"/api/v1/users/*"`, Task 1.3) - confirm `GET /api/v1/users/me` does **not** incorrectly match that same
wildcard the way `/api/v1/lists/me` did (Task 8.2, Step 7); if it does, apply the identical fix: declare
`.requestMatchers(HttpMethod.GET, "/api/v1/users/me").authenticated()` before the broader permit rule.

- [ ] **Step 12: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=UserControllerTest`
Expected: PASS, 4 tests green.

- [ ] **Step 13: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/infrastructure/security/CurrentUser.java src/main/java/com/ludovictemgoua/imdb/domain/model/UserProfile.java src/main/java/com/ludovictemgoua/imdb/domain/model/PublicUserProfile.java src/main/java/com/ludovictemgoua/imdb/application/UpdateProfileRequest.java src/main/java/com/ludovictemgoua/imdb/application/RoleRequest.java src/main/java/com/ludovictemgoua/imdb/application/contracts/UserUseCase.java src/main/java/com/ludovictemgoua/imdb/application/UserUseCaseImpl.java src/main/java/com/ludovictemgoua/imdb/presentation/UserController.java src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java src/test/java/com/ludovictemgoua/imdb/infrastructure/security/CurrentUserTest.java src/test/java/com/ludovictemgoua/imdb/application/UserUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/UserControllerTest.java
git commit -m "Add UserUseCase/UserController: profile management and admin user management"
```

**Phase 1 checkpoint**: run the full suite (`mvn test` then `mvn failsafe:integration-test failsafe:verify`)
before moving to Phase 2 - registration, login, refresh, profile management, admin user management, and
the bootstrap admin are all real and tested at this point, independent of everything that follows.

---

## Phase 2: Core Entity Versioning & Soft Delete

### Task 2.1: Admin-created ID sequences (`V6`)

**Files:**
- Create: `src/main/resources/db/migration/V6__admin_id_sequences.sql`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/AdminIdSequencesIntegrationTest.java`

**Interfaces:**
- Produces: `title_id_seq`/`person_id_seq` Postgres sequences, usable via `nextval('title_id_seq')` /
  `nextval('person_id_seq')` from any later JDBC insert (Phase 3/4).

- [ ] **Step 1: Write the failing integration test**

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql("/fixtures/fixture-data.sql")
class AdminIdSequencesIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void titleIdSequenceStartsAboveTheHighestSeededTconst() {
        Integer maxSeeded = jdbc.queryForObject("SELECT max(tconst) FROM title_basics", Integer.class);
        Integer nextVal = jdbc.queryForObject("SELECT nextval('title_id_seq')", Integer.class);

        assertThat(nextVal).isGreaterThan(maxSeeded);
    }

    @Test
    void personIdSequenceStartsAboveTheHighestSeededNconst() {
        Integer maxSeeded = jdbc.queryForObject("SELECT max(nconst) FROM name_basics", Integer.class);
        Integer nextVal = jdbc.queryForObject("SELECT nextval('person_id_seq')", Integer.class);

        assertThat(nextVal).isGreaterThan(maxSeeded);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=AdminIdSequencesIntegrationTest`
Expected: FAIL - `title_id_seq`/`person_id_seq` don't exist.

- [ ] **Step 3: Create the migration**

```sql
-- Admin-created titles/people (Phase 3/4) need ids that can never collide with the seeded, already-
-- densely-allocated tconst/nconst range from abanda/imdb-postgresql. Starting each sequence one past
-- the current max is done in a DO block, not a plain CREATE SEQUENCE START WITH literal, since the
-- actual max differs across environments (the full dataset here vs. the small fixture Testcontainers
-- and the e2e stack seed) - this migration must work correctly against all three.
DO $$
DECLARE
    next_title_id BIGINT;
    next_person_id BIGINT;
BEGIN
    SELECT COALESCE(max(tconst), 0) + 1 INTO next_title_id FROM title_basics;
    SELECT COALESCE(max(nconst), 0) + 1 INTO next_person_id FROM name_basics;

    EXECUTE format('CREATE SEQUENCE title_id_seq START WITH %s', next_title_id);
    EXECUTE format('CREATE SEQUENCE person_id_seq START WITH %s', next_person_id);
END $$;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=AdminIdSequencesIntegrationTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V6__admin_id_sequences.sql src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/AdminIdSequencesIntegrationTest.java
git commit -m "Add title_id_seq/person_id_seq for admin-created rows (V6)"
```

### Task 2.2: `version`/`deleted_at` on core tables, soft-delete filtering in existing read queries

**Files:**
- Create: `src/main/resources/db/migration/V7__core_entity_version_and_soft_delete.sql`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepository.java`
- Modify: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java`
- Modify: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `version`/`deleted_at` columns on `title_basics`, `name_basics`, `title_ratings`,
  `title_principals`, `title_crew`. A soft-deleted title/person is excluded from search, detail,
  top-rated, and person-name-lookup-by-search - but NOT from `findAnyCommonTitle` enrichment being able
  to reference it if it was already the connecting title before deletion, and NOT from
  `findNameById`/`findNamesByIds` (existing cast/crew references still render a name rather than
  breaking). Admin repository insert/update methods added in Phase 3/4 read/write these two columns
  directly; nothing in this task writes to them yet (no writer exists before Phase 3).

**Known limitation, stated here rather than solved**: `co_star_edges` (the six-degrees materialized view)
is built once from `title_principals`/`title_basics` and refreshed manually (LLD §3.3, PDD §11's open
question on refresh cadence) - a soft-deleted person/title can still appear in a six-degrees path until
the next refresh. This plan does not change that refresh cadence; it's an existing, already-documented
trade-off, not a new one introduced here.

- [ ] **Step 1: Write the failing test additions**

Add to `JdbcTitleRepositoryIntegrationTest`:

```java
    @Test
    void findCoreExcludesASoftDeletedTitle() {
        jdbc.update("UPDATE title_basics SET deleted_at = now() WHERE tconst = 100", Map.of());

        assertThat(repository.findCore(100)).isEmpty();
    }

    @Test
    void searchExcludesASoftDeletedTitle() {
        jdbc.update("UPDATE title_basics SET deleted_at = now() WHERE tconst = 100", Map.of());

        assertThat(repository.search("Few Good Men", 0, 20).content()).extracting("id").doesNotContain("tt0000100");
    }
```

(This requires autowiring a plain `JdbcTemplate jdbc` field in the test class alongside the existing
`JdbcTitleRepository repository` field, and `import org.springframework.jdbc.core.JdbcTemplate;` /
`import java.util.Map;` at the top.)

Add to `JdbcPersonRepositoryIntegrationTest` (create this file if it doesn't already exist, following the
exact structure of `JdbcTitleRepositoryIntegrationTest` - `@Import(TestcontainersConfiguration.class)
@SpringBootTest @Transactional @Sql("/fixtures/fixture-data.sql")`, autowiring `JdbcPersonRepository`):

```java
    @Test
    void findByNameExcludesASoftDeletedPerson() {
        jdbc.update("UPDATE name_basics SET deleted_at = now() WHERE nconst = 1", Map.of());

        assertThat(repository.findByName("Kevin Bacon")).isEmpty();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest,JdbcPersonRepositoryIntegrationTest`
Expected: FAIL - `deleted_at` column doesn't exist yet, so the `UPDATE` in each test itself errors.

- [ ] **Step 3: Create the migration**

```sql
ALTER TABLE title_basics     ADD COLUMN version INTEGER NOT NULL DEFAULT 0, ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE name_basics      ADD COLUMN version INTEGER NOT NULL DEFAULT 0, ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE title_ratings    ADD COLUMN version INTEGER NOT NULL DEFAULT 0, ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE title_principals ADD COLUMN version INTEGER NOT NULL DEFAULT 0, ADD COLUMN deleted_at TIMESTAMPTZ;
ALTER TABLE title_crew       ADD COLUMN version INTEGER NOT NULL DEFAULT 0, ADD COLUMN deleted_at TIMESTAMPTZ;
```

- [ ] **Step 4: Add `AND deleted_at IS NULL` to the discovery queries in `JdbcTitleRepository`**

In `search(...)`, both `dataSql` and `countSql` gain the filter:

```java
        String dataSql = """
                SELECT tconst, primary_title, original_title, title_type, start_year, end_year
                FROM title_basics
                WHERE (primary_title % :query OR original_title % :query) AND deleted_at IS NULL
                ORDER BY similarity(primary_title, :query) DESC
                LIMIT :limit OFFSET :offset
                """;
        String countSql = """
                SELECT count(*) FROM title_basics
                WHERE (primary_title % :query OR original_title % :query) AND deleted_at IS NULL
                """;
```

In `findCore(...)`:

```java
        String sql = """
                SELECT tb.tconst, tb.primary_title, tb.original_title, tb.title_type,
                       tb.start_year, tb.end_year, tb.runtime_minutes, tb.genres,
                       tr.average_rating, tr.num_votes
                FROM title_basics tb
                LEFT JOIN title_ratings tr ON tr.tconst = tb.tconst
                WHERE tb.tconst = :tconst AND tb.deleted_at IS NULL
                """;
```

In `findTopRated(...)`, add `AND tb.deleted_at IS NULL` to the `pool` CTE's `WHERE` clause:

```java
        String sql = """
                WITH pool AS (
                    SELECT tb.tconst, tb.primary_title, tb.start_year, tr.average_rating, tr.num_votes
                    FROM title_basics tb
                    JOIN title_ratings tr ON tr.tconst = tb.tconst
                    WHERE tb.title_type = 'movie'
                      AND genres_as_text(tb.genres) @> ARRAY[:genre]::text[]
                      AND tr.num_votes >= :minVotes
                      AND tb.deleted_at IS NULL
                ),
                stats AS (SELECT AVG(average_rating) AS mean_rating FROM pool)
                SELECT p.tconst, p.primary_title, p.start_year, p.average_rating, p.num_votes,
                       (p.num_votes::numeric / (p.num_votes + :minVotes)) * p.average_rating
                       + (:minVotes::numeric / (p.num_votes + :minVotes)) * s.mean_rating AS weighted_rating
                FROM pool p CROSS JOIN stats s
                ORDER BY weighted_rating DESC
                LIMIT :limit
                """;
```

`findDirectors`/`findWriters`/`findTopCast`/`countCast`/`findAnyCommonTitle` are deliberately left
unchanged - they render or enrich *existing* references (a title/person that might have since been
soft-deleted but was real cast/crew at the time), matching the design decision that soft-deleting a title
doesn't retroactively break historical references to it (`docs/crud-expansion-design.md` §6.4).

- [ ] **Step 5: Add `AND deleted_at IS NULL` to `findByName` in `JdbcPersonRepository`**

```java
        String sql = """
                SELECT nconst, primary_name, birth_year, known_for_titles
                FROM name_basics
                WHERE primary_name % :name AND deleted_at IS NULL
                ORDER BY similarity(primary_name, :name) DESC
                LIMIT 10
                """;
```

`findNameById`/`findNamesByIds` are left unchanged for the same historical-reference reason as above.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest,JdbcPersonRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Run the full suite to confirm nothing regressed**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS - all existing tests remain green; the new `version`/`deleted_at` columns are additive
(`NOT NULL DEFAULT 0` / nullable) so no existing insert statement anywhere in the codebase needs to
change.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V7__core_entity_version_and_soft_delete.sql src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepositoryIntegrationTest.java
git commit -m "Add version/deleted_at to core tables; filter soft-deleted rows from discovery queries"
```

**Phase 2 checkpoint**: core tables can now support versioned updates and soft deletes, but nothing writes
to them yet - Phase 3 adds the first writer.

---

## Phase 3: Admin CRUD - Titles

### Task 3.1: `TitleRepository` write methods (insert/update/delete/crew/rating)

**Files:**
- Modify: `src/main/java/com/ludovictemgoua/imdb/domain/repository/TitleRepository.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java`
- Modify: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `TitleRepository.insertTitle(...) -> TitleCore`, `updateTitle(int tconst, ..., int expectedVersion) -> WriteResult`, `softDeleteTitle(int tconst) -> WriteResult`, `upsertCrew(int tconst, List<Integer> directorIds, List<Integer> writerIds) -> WriteResult`, `upsertRating(int tconst, double averageRating, int numVotes) -> WriteResult`, `deleteRating(int tconst) -> WriteResult`. `TitleCore.version()` - add a `version` component to the existing `TitleCore` record (Task's Step 1 shows the exact new record shape; every caller of the constructor - `mapCore` in this file, and the record's one other reader in `TitleDetailUseCaseImpl` - is updated in this same task).

- [ ] **Step 1: Write the failing integration tests**

Add to `JdbcTitleRepositoryIntegrationTest`:

```java
    @Test
    void insertTitleCreatesARowWithVersionZero() {
        var created = repository.insertTitle("New Movie", "New Movie", "movie", 2024, null, 120, List.of("Drama"));

        assertThat(created.version()).isEqualTo(0);
        assertThat(repository.findCore(ImdbIds.parseTitleId(created.id())).orElseThrow().primaryTitle())
                .isEqualTo("New Movie");
    }

    @Test
    void insertedTitleIdIsAboveTheSeededRange() {
        var created = repository.insertTitle("Another Movie", "Another Movie", "movie", 2024, null, 90, List.of());

        assertThat(ImdbIds.parseTitleId(created.id())).isGreaterThan(200);
    }

    @Test
    void updateTitleBumpsVersionAndPersists() {
        var created = repository.insertTitle("Old Name", "Old Name", "movie", 2020, null, 100, List.of("Drama"));
        int tconst = ImdbIds.parseTitleId(created.id());

        var result = repository.updateTitle(
                tconst, "New Name", "New Name", "movie", 2021, null, 110, List.of("Comedy"), created.version());

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.SUCCESS);
        var updated = repository.findCore(tconst).orElseThrow();
        assertThat(updated.primaryTitle()).isEqualTo("New Name");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void updateTitleReturnsVersionConflictOnStaleVersion() {
        var created = repository.insertTitle("Stale Test", "Stale Test", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        var result = repository.updateTitle(
                tconst, "New Name", "New Name", "movie", 2021, null, 110, List.of(), created.version() + 1);

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.VERSION_CONFLICT);
    }

    @Test
    void softDeleteTitleExcludesItFromFindCore() {
        var created = repository.insertTitle("Delete Me", "Delete Me", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        repository.softDeleteTitle(tconst);

        assertThat(repository.findCore(tconst)).isEmpty();
    }

    @Test
    void upsertRatingThenDeleteRatingRoundTrips() {
        var created = repository.insertTitle("Rating Test", "Rating Test", "movie", 2020, null, 100, List.of());
        int tconst = ImdbIds.parseTitleId(created.id());

        repository.upsertRating(tconst, 7.5, 1000);
        assertThat(repository.findCore(tconst).orElseThrow().averageRating()).isEqualTo(7.5);

        repository.deleteRating(tconst);
        assertThat(repository.findCore(tconst).orElseThrow().averageRating()).isNull();
    }
```

Add `import com.ludovictemgoua.imdb.utils.ImdbIds;` and `import java.util.List;` to the test's imports if not
already present (`List` already is, via the existing `findTopRated` test).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest`
Expected: FAIL - the new repository methods don't exist yet.

- [ ] **Step 3: Add `version` to `TitleCore` and update its two existing usages**

`TitleCore` currently ends in `Integer runtimeMinutes, List<String> genres, Double averageRating, Integer numVotes)` (check the actual file for its exact full signature) - add `, int version` as the final component. Update `JdbcTitleRepository.mapCore` to read `rs.getInt("version")` as the last constructor argument, and update `TitleDetailUseCaseImpl.getDetail` - it destructures `core` into a `TitleDetail`; `TitleDetail` itself does **not** need a `version` field (it's a read response, not a write target), so `TitleDetailUseCaseImpl` needs no change beyond compiling against the new `TitleCore` shape (it doesn't reference `core.version()`).

- [ ] **Step 4: Add the new methods to `TitleRepository`**

```java
    TitleCore insertTitle(String primaryTitle, String originalTitle, String titleType,
                          Integer startYear, Integer endYear, Integer runtimeMinutes, List<String> genres);

    WriteResult updateTitle(int tconst, String primaryTitle, String originalTitle, String titleType,
                            Integer startYear, Integer endYear, Integer runtimeMinutes,
                            List<String> genres, int expectedVersion);

    WriteResult softDeleteTitle(int tconst);

    WriteResult upsertCrew(int tconst, List<Integer> directorIds, List<Integer> writerIds);

    WriteResult upsertRating(int tconst, double averageRating, int numVotes);

    WriteResult deleteRating(int tconst);
```

(Add `import com.ludovictemgoua.imdb.domain.repository.WriteResult;` is unnecessary since `WriteResult` is
already in this same package; add `import java.util.List;` if not already present.)

- [ ] **Step 5: Implement the new methods in `JdbcTitleRepository`**

```java
    @Override
    public TitleCore insertTitle(String primaryTitle, String originalTitle, String titleType,
                                 Integer startYear, Integer endYear, Integer runtimeMinutes, List<String> genres) {
        String sql = """
                INSERT INTO title_basics (tconst, primary_title, original_title, title_type,
                                          start_year, end_year, runtime_minutes, genres)
                VALUES (nextval('title_id_seq'), :primaryTitle, :originalTitle, :titleType,
                        :startYear, :endYear, :runtimeMinutes, :genres)
                RETURNING tconst
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryTitle", primaryTitle).addValue("originalTitle", originalTitle)
                .addValue("titleType", titleType).addValue("startYear", startYear)
                .addValue("endYear", endYear).addValue("runtimeMinutes", runtimeMinutes)
                .addValue("genres", genres.toArray(new String[0]), java.sql.Types.ARRAY, "text");
        int tconst = jdbc.queryForObject(sql, params, Integer.class);
        return findCore(tconst).orElseThrow();
    }

    @Override
    public WriteResult updateTitle(int tconst, String primaryTitle, String originalTitle, String titleType,
                                   Integer startYear, Integer endYear, Integer runtimeMinutes,
                                   List<String> genres, int expectedVersion) {
        if (findCore(tconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                UPDATE title_basics
                SET primary_title = :primaryTitle, original_title = :originalTitle, title_type = :titleType,
                    start_year = :startYear, end_year = :endYear, runtime_minutes = :runtimeMinutes,
                    genres = :genres, version = version + 1
                WHERE tconst = :tconst AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryTitle", primaryTitle).addValue("originalTitle", originalTitle)
                .addValue("titleType", titleType).addValue("startYear", startYear)
                .addValue("endYear", endYear).addValue("runtimeMinutes", runtimeMinutes)
                .addValue("genres", genres.toArray(new String[0]), java.sql.Types.ARRAY, "text")
                .addValue("tconst", tconst).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDeleteTitle(int tconst) {
        if (findCore(tconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        jdbc.update("UPDATE title_basics SET deleted_at = now() WHERE tconst = :tconst", Map.of("tconst", tconst));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult upsertCrew(int tconst, List<Integer> directorIds, List<Integer> writerIds) {
        if (findCore(tconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                INSERT INTO title_crew (tconst, directors, writers)
                VALUES (:tconst, :directors, :writers)
                ON CONFLICT (tconst) DO UPDATE SET directors = :directors, writers = :writers, version = title_crew.version + 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tconst", tconst)
                .addValue("directors", directorIds.toArray(new Integer[0]), java.sql.Types.ARRAY, "integer")
                .addValue("writers", writerIds.toArray(new Integer[0]), java.sql.Types.ARRAY, "integer");
        jdbc.update(sql, params);
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult upsertRating(int tconst, double averageRating, int numVotes) {
        if (findCore(tconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                INSERT INTO title_ratings (tconst, average_rating, num_votes)
                VALUES (:tconst, :averageRating, :numVotes)
                ON CONFLICT (tconst) DO UPDATE SET average_rating = :averageRating, num_votes = :numVotes,
                                                    version = title_ratings.version + 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("tconst", tconst).addValue("averageRating", averageRating).addValue("numVotes", numVotes);
        jdbc.update(sql, params);
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult deleteRating(int tconst) {
        int updated = jdbc.update("UPDATE title_ratings SET deleted_at = now() WHERE tconst = :tconst AND deleted_at IS NULL",
                Map.of("tconst", tconst));
        return updated == 0 ? WriteResult.NOT_FOUND : WriteResult.SUCCESS;
    }
```

`upsertRating`/`deleteRating` require `findCore`'s `LEFT JOIN title_ratings` to also filter
`(tr.deleted_at IS NULL OR tr.deleted_at IS NULL)` - add `AND (tr.tconst IS NULL OR tr.deleted_at IS NULL)`
to `findCore`'s `WHERE` clause so a deleted rating correctly shows as no rating rather than a stale one:

```java
                WHERE tb.tconst = :tconst AND tb.deleted_at IS NULL
                  AND (tr.tconst IS NULL OR tr.deleted_at IS NULL)
```

`title_ratings`/`title_crew` need a unique constraint on `tconst` for the `ON CONFLICT` clauses above to
work - add this to `V7__core_entity_version_and_soft_delete.sql` from Task 2.2 (both tables already have
`tconst` as their primary key per the `V0` base schema, so `ON CONFLICT (tconst)` already targets a real
unique constraint with no migration change needed - confirm this against `V0__base_schema.sql` before
writing Step 5's SQL; if either table's PK is composite instead, change `ON CONFLICT (tconst)` to that
table's actual primary key column list).

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest`
Expected: PASS, all 6 new tests plus the existing ones green.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/domain/repository/TitleRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java src/main/java/com/ludovictemgoua/imdb/domain/model/TitleCore.java src/main/java/com/ludovictemgoua/imdb/application/TitleDetailUseCaseImpl.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java
git commit -m "Add TitleRepository write methods: insert/update/soft-delete/crew/rating"
```

### Task 3.2: `TitleAdminUseCase`, cache-evicting decorator, `TitleController` admin endpoints

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/TitleAdminUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/CreateTitleRequest.java`, `UpdateTitleRequest.java`, `PatchTitleRequest.java`, `CrewRequest.java`, `RatingRequest.java` (records)
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingTitleAdminUseCase.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/presentation/TitleController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/TitleControllerTest.java` (extend)

**Interfaces:**
- Consumes: `TitleRepository` write methods (Task 3.1), `WriteResult` (Task 1.1)
- Produces: `TitleAdminUseCase` with `create`, `update`, `patch`, `delete`, `upsertCrew`, `upsertRating`,
  `deleteRating` - every admin write endpoint in `docs/crud-expansion-design.md` §5.1/§5.3 routes through
  this one interface (title-scoped admin operations grouped by domain cohesion, per §8).

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TitleAdminUseCaseImplTest {

    @Mock
    TitleRepository titleRepository;

    @Test
    void createDelegatesToInsertTitle() {
        var created = new TitleCore("tt0000300", "New", "New", "movie", 2024, null, 100, List.of(), null, null, 0);
        given(titleRepository.insertTitle("New", "New", "movie", 2024, null, 100, List.of()))
                .willReturn(created);

        var result = new TitleAdminUseCaseImpl(titleRepository)
                .create(new CreateTitleRequest("New", "New", "movie", 2024, null, 100, List.of()));

        assertThat(result.id()).isEqualTo("tt0000300");
    }

    @Test
    void updateThrowsConflictOnVersionMismatch() {
        given(titleRepository.updateTitle(300, "New", "New", "movie", 2024, null, 100, List.of(), 0))
                .willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.update("tt0000300",
                new UpdateTitleRequest("New", "New", "movie", 2024, null, 100, List.of(), 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateThrowsNotFoundWhenTheTitleDoesNotExist() {
        given(titleRepository.updateTitle(300, "New", "New", "movie", 2024, null, 100, List.of(), 0))
                .willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.update("tt0000300",
                new UpdateTitleRequest("New", "New", "movie", 2024, null, 100, List.of(), 0)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteThrowsNotFoundWhenTheTitleDoesNotExist() {
        given(titleRepository.softDeleteTitle(300)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.delete("tt0000300")).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleAdminUseCaseImplTest`
Expected: FAIL - `TitleAdminUseCaseImpl`/the request records don't exist yet.

- [ ] **Step 3: Create the request records**

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTitleRequest(@NotBlank String primaryTitle, @NotBlank String originalTitle,
                                  @NotBlank String titleType, Integer startYear, Integer endYear,
                                  Integer runtimeMinutes, List<String> genres) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdateTitleRequest(@NotBlank String primaryTitle, @NotBlank String originalTitle,
                                  @NotBlank String titleType, Integer startYear, Integer endYear,
                                  Integer runtimeMinutes, List<String> genres, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import java.util.List;

// Every field nullable/absent - merge-patch semantics (docs/crud-expansion-design.md §6.5): only
// fields present in the JSON body are applied, everything else is left as-is on the existing row.
public record PatchTitleRequest(String primaryTitle, String originalTitle, String titleType,
                                 Integer startYear, Integer endYear, Integer runtimeMinutes,
                                 List<String> genres, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import java.util.List;

public record CrewRequest(List<String> directors, List<String> writers) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public record RatingRequest(@DecimalMin("0.0") @DecimalMax("10.0") double averageRating,
                             @Min(0) int numVotes) {
}
```

- [ ] **Step 4: Create `TitleAdminUseCase`/`TitleAdminUseCaseImpl`**

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.domain.model.TitleCore;

public interface TitleAdminUseCase {

    TitleCore create(CreateTitleRequest request);

    TitleCore update(String titleId, UpdateTitleRequest request);

    TitleCore patch(String titleId, PatchTitleRequest request);

    void delete(String titleId);

    void upsertCrew(String titleId, CrewRequest request);

    void upsertRating(String titleId, RatingRequest request);

    void deleteRating(String titleId);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TitleAdminUseCaseImpl implements TitleAdminUseCase {

    private final TitleRepository titleRepository;

    public TitleAdminUseCaseImpl(TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    @Override
    public TitleCore create(CreateTitleRequest request) {
        return titleRepository.insertTitle(request.primaryTitle(), request.originalTitle(), request.titleType(),
                request.startYear(), request.endYear(), request.runtimeMinutes(), request.genres());
    }

    @Override
    public TitleCore update(String titleId, UpdateTitleRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.updateTitle(tconst, request.primaryTitle(), request.originalTitle(),
                request.titleType(), request.startYear(), request.endYear(), request.runtimeMinutes(),
                request.genres(), request.version()), titleId);
        return titleRepository.findCore(tconst).orElseThrow();
    }

    @Override
    public TitleCore patch(String titleId, PatchTitleRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        TitleCore current = titleRepository.findCore(tconst)
                .orElseThrow(() -> new NotFoundException("No title with id " + titleId));
        handle(titleRepository.updateTitle(tconst,
                request.primaryTitle() != null ? request.primaryTitle() : current.primaryTitle(),
                request.originalTitle() != null ? request.originalTitle() : current.originalTitle(),
                request.titleType() != null ? request.titleType() : current.titleType(),
                request.startYear() != null ? request.startYear() : current.startYear(),
                request.endYear() != null ? request.endYear() : current.endYear(),
                request.runtimeMinutes() != null ? request.runtimeMinutes() : current.runtimeMinutes(),
                request.genres() != null ? request.genres() : current.genres(),
                request.version()), titleId);
        return titleRepository.findCore(tconst).orElseThrow();
    }

    @Override
    public void delete(String titleId) {
        handle(titleRepository.softDeleteTitle(ImdbIds.parseTitleId(titleId)), titleId);
    }

    @Override
    public void upsertCrew(String titleId, CrewRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        List<Integer> directorIds = toPersonIds(request.directors());
        List<Integer> writerIds = toPersonIds(request.writers());
        handle(titleRepository.upsertCrew(tconst, directorIds, writerIds), titleId);
    }

    @Override
    public void upsertRating(String titleId, RatingRequest request) {
        handle(titleRepository.upsertRating(ImdbIds.parseTitleId(titleId),
                request.averageRating(), request.numVotes()), titleId);
    }

    @Override
    public void deleteRating(String titleId) {
        handle(titleRepository.deleteRating(ImdbIds.parseTitleId(titleId)), titleId);
    }

    private static List<Integer> toPersonIds(List<String> personIds) {
        return personIds == null ? List.of()
                : personIds.stream().map(ImdbIds::parsePersonId).collect(Collectors.toList());
    }

    private static void handle(WriteResult result, String titleId) {
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No title with id " + titleId);
            case VERSION_CONFLICT -> throw new ConflictException(
                    "Title " + titleId + " was modified by someone else - refresh and retry");
            case SUCCESS -> { }
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleAdminUseCaseImplTest`
Expected: PASS, 4 tests green.

- [ ] **Step 6: Create the cache-evicting decorator**

```java
package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.TitleAdminUseCaseImpl;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// Precise title-detail eviction on any write affecting that title; a rating write also clears the
// entire top-rated region (allEntries) since there's no cheap way to know which genre/limit/minVotes
// combinations it affects - the same coarse-but-correct trade-off documented in
// docs/crud-expansion-design.md §6.2. Admin writes are expected to be infrequent, so full-region
// eviction here is cheap in practice.
@Service
@Primary
public class CachingTitleAdminUseCase implements TitleAdminUseCase {

    private final TitleAdminUseCaseImpl delegate;

    public CachingTitleAdminUseCase(TitleAdminUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public TitleCore create(CreateTitleRequest request) {
        return delegate.create(request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public TitleCore update(String titleId, UpdateTitleRequest request) {
        return delegate.update(titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public TitleCore patch(String titleId, PatchTitleRequest request) {
        return delegate.patch(titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void delete(String titleId) {
        delegate.delete(titleId);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void upsertCrew(String titleId, CrewRequest request) {
        delegate.upsertCrew(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "top-rated", allEntries = true)
    })
    public void upsertRating(String titleId, RatingRequest request) {
        delegate.upsertRating(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "top-rated", allEntries = true)
    })
    public void deleteRating(String titleId) {
        delegate.deleteRating(titleId);
    }
}
```

- [ ] **Step 7: Write the failing controller test additions**

Add to `TitleControllerTest` (needs a new `@MockitoBean TitleAdminUseCase titleAdminUseCase;` field and
`@Import(...)` of nothing extra - `@WebMvcTest` auto-mocks any constructor dependency not already present):

```java
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase titleAdminUseCase;

    @Test
    void createTitleRequiresAdminRole() throws Exception {
        mockMvc.perform(post("/api/v1/titles")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("1").roles("USER"))
                        .contentType("application/json")
                        .content("""
                                {"primaryTitle":"New","originalTitle":"New","titleType":"movie","genres":[]}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    void createTitleSucceedsForAdmin() throws Exception {
        var created = new com.ludovictemgoua.imdb.domain.model.TitleCore(
                "tt0000300", "New", "New", "movie", 2024, null, 100, List.of(), null, null, 0);
        given(titleAdminUseCase.create(org.mockito.ArgumentMatchers.any())).willReturn(created);

        mockMvc.perform(post("/api/v1/titles")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("1").roles("ADMIN"))
                        .contentType("application/json")
                        .content("""
                                {"primaryTitle":"New","originalTitle":"New","titleType":"movie","genres":[]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("tt0000300"));
    }
```

Add `import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;` and
`import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;` to the test's
imports (`post`/`get` may already partially be there - add whichever verbs are missing).

- [ ] **Step 8: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleControllerTest`
Expected: FAIL - `TitleController` has no `POST /api/v1/titles` mapping yet.

- [ ] **Step 9: Add the admin endpoints to `TitleController`**

```java
    private final TitleAdminUseCase titleAdminUseCase;

    public TitleController(TitleSearchUseCase searchUseCase, TitleDetailUseCase detailUseCase,
                            TitleAdminUseCase titleAdminUseCase) {
        this.searchUseCase = searchUseCase;
        this.detailUseCase = detailUseCase;
        this.titleAdminUseCase = titleAdminUseCase;
    }

    @PostMapping
    @org.springframework.http.HttpStatus.CREATED // placeholder marker removed below - see @ResponseStatus line
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public TitleCore create(@jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                             com.ludovictemgoua.imdb.application.CreateTitleRequest request) {
        return titleAdminUseCase.create(request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{titleId}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public TitleCore update(@PathVariable String titleId,
                            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                            com.ludovictemgoua.imdb.application.UpdateTitleRequest request) {
        return titleAdminUseCase.update(titleId, request);
    }

    @org.springframework.web.bind.annotation.PatchMapping("/{titleId}")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public TitleCore patch(@PathVariable String titleId,
                           @org.springframework.web.bind.annotation.RequestBody
                           com.ludovictemgoua.imdb.application.PatchTitleRequest request) {
        return titleAdminUseCase.patch(titleId, request);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{titleId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String titleId) {
        titleAdminUseCase.delete(titleId);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{titleId}/crew")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void upsertCrew(@PathVariable String titleId,
                           @org.springframework.web.bind.annotation.RequestBody
                           com.ludovictemgoua.imdb.application.CrewRequest request) {
        titleAdminUseCase.upsertCrew(titleId, request);
    }

    @org.springframework.web.bind.annotation.PutMapping("/{titleId}/rating")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void upsertRating(@PathVariable String titleId,
                             @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                             com.ludovictemgoua.imdb.application.RatingRequest request) {
        titleAdminUseCase.upsertRating(titleId, request);
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{titleId}/rating")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public void deleteRating(@PathVariable String titleId) {
        titleAdminUseCase.deleteRating(titleId);
    }
```

Replace the fully-qualified names above with proper `import` statements at the top of the file (matching
the file's existing style - it already imports `PagedResult`/`TitleDetail`/`TitleSummary` etc. by name, so
do the same for every class used above: `TitleCore`, `TitleAdminUseCase`, `CreateTitleRequest`,
`UpdateTitleRequest`, `PatchTitleRequest`, `CrewRequest`, `RatingRequest`, `PostMapping`, `PutMapping`,
`PatchMapping`, `DeleteMapping`, `ResponseStatus`, `RequestBody`, `HttpStatus`, `Valid`, `PreAuthorize`).
Delete the stray `@org.springframework.http.HttpStatus.CREATED` marker line above - it isn't valid
annotation syntax and was left in only to flag "this is where `@ResponseStatus(CREATED)` goes" during
planning; the real code has just the one `@ResponseStatus` line beneath it.

- [ ] **Step 10: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleControllerTest`
Expected: PASS, all tests (existing + new) green.

- [ ] **Step 11: Run the full unit suite, then the full integration suite**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/ src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingTitleAdminUseCase.java src/main/java/com/ludovictemgoua/imdb/presentation/TitleController.java src/test/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/TitleControllerTest.java
git commit -m "Add admin CRUD for titles (create/update/patch/delete/crew/rating) with cache eviction"
```

### Task 3.3: Reduce the `title-search` cache TTL

**Files:**
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CacheConfig.java`

**Interfaces:** none new - this only changes a `RedisCacheConfiguration` applied to the existing
`title-search` cache region.

`title-search` is keyed by `query:page:size` (LLD §6) - an admin title write has no cheap way to know
which of those arbitrary combinations it affects, so precise eviction (like `title-detail`) isn't
possible and full-region eviction on every title write would defeat the cache almost entirely (title
writes and title searches share the same cache region's traffic). `docs/crud-expansion-design.md` §6.2's
resolution: accept a bounded staleness window instead, by shortening this one region's TTL from the
default 24h to 15 minutes.

- [ ] **Step 1: Write the failing test**

```java
package com.ludovictemgoua.imdb.infrastructure.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CacheConfigTest {

    @Autowired
    RedisCacheManager cacheManager;

    @Test
    void titleSearchCacheHasAFifteenMinuteTtlNotTheDefaultTwentyFourHours() {
        var ttl = cacheManager.getCache("title-search").getNativeCache().toString();
        // RedisCache doesn't expose its configured TTL directly via a public getter on getNativeCache();
        // assert against the cache's own configuration object instead:
        var config = ((org.springframework.data.redis.cache.RedisCache) cacheManager.getCache("title-search"))
                .getCacheConfiguration();

        assertThat(config.getTtl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void titleDetailCacheKeepsTheDefaultTwentyFourHourTtl() {
        var config = ((org.springframework.data.redis.cache.RedisCache) cacheManager.getCache("title-detail"))
                .getCacheConfiguration();

        assertThat(config.getTtl()).isEqualTo(Duration.ofHours(24));
    }
}
```

This test needs a real Spring context with the actual `RedisCacheManager` bean (not Testcontainers Redis
specifically - `@SpringBootTest` alone will fail to start without a Redis connection, so add
`@Import(com.ludovictemgoua.imdb.TestcontainersConfiguration.class)` to the class, matching every other
`@SpringBootTest` in this codebase).

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=CacheConfigTest`
Expected: FAIL - `title-search` still has the default 24h TTL. (Note: this test class needs to be named
`*IntegrationTest.java` or added to the Failsafe `<includes>` pattern in `pom.xml` to actually run under
`failsafe:verify` - rename it to `CacheConfigIntegrationTest` to match the existing convention rather than
adding a one-off Failsafe include pattern for a single class.)

- [ ] **Step 3: Add the per-cache TTL override**

```java
        RedisCacheConfiguration searchCacheConfig = defaults.entryTtl(Duration.ofMinutes(15));

        return RedisCacheManager.builder(redisCacheWriter)
                .cacheDefaults(defaults)
                .withCacheConfiguration("title-search", searchCacheConfig)
                .initialCacheNames(CACHE_NAMES)
                .build();
```

(Replace the existing `return RedisCacheManager.builder(redisCacheWriter)...build();` block in
`cacheManager(...)` with the above - `searchCacheConfig` is `defaults` with just the TTL overridden,
inheriting the same serializer configuration.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=CacheConfigIntegrationTest`
Expected: PASS, both tests green.

- [ ] **Step 5: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CacheConfig.java src/test/java/com/ludovictemgoua/imdb/infrastructure/cache/CacheConfigIntegrationTest.java
git commit -m "Shorten the title-search cache TTL to 15 minutes now that title writes exist"
```

**Phase 3 checkpoint**: admin title CRUD is fully live, gated by `@PreAuthorize("hasRole('ADMIN')")`, with
correct cache eviction (precise for `title-detail`/`top-rated`, a shortened TTL for the un-evictable
`title-search`). This is the template Phase 4 (People) follows.

---

## Phase 4: Admin CRUD - People

### Task 4.1: `PersonCore`, `PersonRepository` write methods

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/PersonCore.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/domain/repository/PersonRepository.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepository.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepositoryIntegrationTest.java` (create if Task 2.2 didn't already, extend either way)

**Interfaces:**
- Produces: `PersonCore(String id, String primaryName, Integer birthYear, Integer deathYear, List<String> primaryProfession, int version)`. `PersonRepository.findCore(int nconst) -> Optional<PersonCore>`, `insertPerson(...) -> PersonCore`, `updatePerson(int nconst, ..., int expectedVersion) -> WriteResult`, `softDeletePerson(int nconst) -> WriteResult`. This `findCore` is internal-only (no controller exposes a plain "get person" read endpoint - matches `docs/crud-expansion-design.md` §5.2 exactly, which lists only the four write endpoints).

- [ ] **Step 1: Write the failing integration tests**

```java
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

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.SUCCESS);
        assertThat(repository.findCore(nconst).orElseThrow().primaryName()).isEqualTo("New Name");
    }

    @Test
    void updatePersonReturnsVersionConflictOnStaleVersion() {
        var created = repository.insertPerson("Stale", null, null, List.of());
        int nconst = ImdbIds.parsePersonId(created.id());

        var result = repository.updatePerson(nconst, "New Name", null, null, List.of(), created.version() + 1);

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.VERSION_CONFLICT);
    }

    @Test
    void softDeletePersonExcludesThemFromFindCore() {
        var created = repository.insertPerson("Delete Me", null, null, List.of());
        int nconst = ImdbIds.parsePersonId(created.id());

        repository.softDeletePerson(nconst);

        assertThat(repository.findCore(nconst)).isEmpty();
    }
```

If `JdbcPersonRepositoryIntegrationTest` doesn't exist yet, create it now with the full class structure
(copy `JdbcTitleRepositoryIntegrationTest`'s header exactly:
`@Import(TestcontainersConfiguration.class) @SpringBootTest @Transactional @Sql("/fixtures/fixture-data.sql")`,
autowiring `JdbcPersonRepository repository`), and include Task 2.2's `findByNameExcludesASoftDeletedPerson`
test in it too if that's still pending.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcPersonRepositoryIntegrationTest`
Expected: FAIL - `PersonCore`/the new repository methods don't exist yet.

- [ ] **Step 3: Create `PersonCore`**

```java
package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PersonCore(String id, String primaryName, Integer birthYear, Integer deathYear,
                          List<String> primaryProfession, int version) {
}
```

- [ ] **Step 4: Add the new methods to `PersonRepository`**

```java
    PersonCore insertPerson(String primaryName, Integer birthYear, Integer deathYear, List<String> primaryProfession);

    Optional<PersonCore> findCore(int nconst);

    com.ludovictemgoua.imdb.domain.repository.WriteResult updatePerson(
            int nconst, String primaryName, Integer birthYear, Integer deathYear,
            List<String> primaryProfession, int expectedVersion);

    com.ludovictemgoua.imdb.domain.repository.WriteResult softDeletePerson(int nconst);
```

Add `import com.ludovictemgoua.imdb.domain.model.PersonCore;`, `import java.util.List;`, and
`import java.util.Optional;` to the top of `PersonRepository.java` (replace the fully-qualified
`WriteResult` references above with a plain `import com.ludovictemgoua.imdb.domain.repository.WriteResult;`
- it's the same package as this file, so a bare `WriteResult` reference works with no import at all;
either is fine, prefer the bare reference since it's simpler).

- [ ] **Step 5: Implement the new methods in `JdbcPersonRepository`**

```java
    @Override
    public PersonCore insertPerson(String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession) {
        String sql = """
                INSERT INTO name_basics (nconst, primary_name, birth_year, death_year, primary_profession)
                VALUES (nextval('person_id_seq'), :primaryName, :birthYear, :deathYear, :primaryProfession)
                RETURNING nconst
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryName", primaryName).addValue("birthYear", birthYear)
                .addValue("deathYear", deathYear)
                .addValue("primaryProfession", primaryProfession.toArray(new String[0]), java.sql.Types.ARRAY, "text");
        int nconst = jdbc.queryForObject(sql, params, Integer.class);
        return findCore(nconst).orElseThrow();
    }

    @Override
    public Optional<PersonCore> findCore(int nconst) {
        String sql = """
                SELECT nconst, primary_name, birth_year, death_year, primary_profession, version
                FROM name_basics WHERE nconst = :nconst AND deleted_at IS NULL
                """;
        return jdbc.query(sql, Map.of("nconst", nconst), JdbcPersonRepository::mapCore).stream().findFirst();
    }

    @Override
    public WriteResult updatePerson(int nconst, String primaryName, Integer birthYear, Integer deathYear,
                                    List<String> primaryProfession, int expectedVersion) {
        if (findCore(nconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                UPDATE name_basics
                SET primary_name = :primaryName, birth_year = :birthYear, death_year = :deathYear,
                    primary_profession = :primaryProfession, version = version + 1
                WHERE nconst = :nconst AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryName", primaryName).addValue("birthYear", birthYear)
                .addValue("deathYear", deathYear)
                .addValue("primaryProfession", primaryProfession.toArray(new String[0]), java.sql.Types.ARRAY, "text")
                .addValue("nconst", nconst).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDeletePerson(int nconst) {
        if (findCore(nconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        jdbc.update("UPDATE name_basics SET deleted_at = now() WHERE nconst = :nconst", Map.of("nconst", nconst));
        return WriteResult.SUCCESS;
    }

    private static PersonCore mapCore(ResultSet rs, int rowNum) throws SQLException {
        return new PersonCore(ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                (Integer) rs.getObject("birth_year"), (Integer) rs.getObject("death_year"),
                toStringList(rs.getArray("primary_profession")), rs.getInt("version"));
    }

    private static List<String> toStringList(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        return List.of((String[]) sqlArray.getArray());
    }
```

Add `import com.ludovictemgoua.imdb.domain.model.PersonCore;` and
`import com.ludovictemgoua.imdb.domain.repository.WriteResult;` to `JdbcPersonRepository.java`'s imports.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcPersonRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/domain/model/PersonCore.java src/main/java/com/ludovictemgoua/imdb/domain/repository/PersonRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcPersonRepositoryIntegrationTest.java
git commit -m "Add PersonRepository write methods: insert/update/soft-delete"
```

### Task 4.2: `PersonAdminUseCase`, `PersonController` admin endpoints

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/PersonAdminUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/PersonAdminUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/CreatePersonRequest.java`, `UpdatePersonRequest.java`, `PatchPersonRequest.java` (records)
- Modify: `src/main/java/com/ludovictemgoua/imdb/presentation/PersonController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/PersonAdminUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/PersonControllerTest.java` (extend)

**Interfaces:**
- Consumes: `PersonRepository` write methods (Task 4.1)
- Produces: `PersonAdminUseCase.create/update/patch/delete`, all four gated `@PreAuthorize("hasRole('ADMIN')")`
  on `PersonController`. No cache eviction needed here directly (nothing caches a person by id today - the
  `six-degrees` cache is evicted from `CachingCoStarGraphRepository`, wired in Phase 5 alongside principals,
  since that's the cache region a person write can actually invalidate).

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PersonAdminUseCaseImplTest {

    @Mock
    PersonRepository personRepository;

    @Test
    void createDelegatesToInsertPerson() {
        var created = new PersonCore("nm0000011", "New Person", null, null, List.of(), 0);
        given(personRepository.insertPerson("New Person", null, null, List.of())).willReturn(created);

        var result = new PersonAdminUseCaseImpl(personRepository)
                .create(new CreatePersonRequest("New Person", null, null, List.of()));

        assertThat(result.id()).isEqualTo("nm0000011");
    }

    @Test
    void updateThrowsConflictOnVersionMismatch() {
        given(personRepository.updatePerson(11, "New", null, null, List.of(), 0))
                .willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new PersonAdminUseCaseImpl(personRepository);

        assertThatThrownBy(() -> useCase.update("nm0000011",
                new UpdatePersonRequest("New", null, null, List.of(), 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteThrowsNotFoundWhenThePersonDoesNotExist() {
        given(personRepository.softDeletePerson(11)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new PersonAdminUseCaseImpl(personRepository);

        assertThatThrownBy(() -> useCase.delete("nm0000011")).isInstanceOf(NotFoundException.class);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=PersonAdminUseCaseImplTest`
Expected: FAIL.

- [ ] **Step 3: Create the request records**

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreatePersonRequest(@NotBlank String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdatePersonRequest(@NotBlank String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import java.util.List;

public record PatchPersonRequest(String primaryName, Integer birthYear, Integer deathYear,
                                  List<String> primaryProfession, int version) {
}
```

- [ ] **Step 4: Create `PersonAdminUseCase`/`PersonAdminUseCaseImpl`**

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.UpdatePersonRequest;
import com.ludovictemgoua.imdb.domain.model.PersonCore;

public interface PersonAdminUseCase {

    PersonCore create(CreatePersonRequest request);

    PersonCore update(String personId, UpdatePersonRequest request);

    PersonCore patch(String personId, PatchPersonRequest request);

    void delete(String personId);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class PersonAdminUseCaseImpl implements PersonAdminUseCase {

    private final PersonRepository personRepository;

    public PersonAdminUseCaseImpl(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @Override
    public PersonCore create(CreatePersonRequest request) {
        return personRepository.insertPerson(
                request.primaryName(), request.birthYear(), request.deathYear(), request.primaryProfession());
    }

    @Override
    public PersonCore update(String personId, UpdatePersonRequest request) {
        int nconst = ImdbIds.parsePersonId(personId);
        handle(personRepository.updatePerson(nconst, request.primaryName(), request.birthYear(),
                request.deathYear(), request.primaryProfession(), request.version()), personId);
        return personRepository.findCore(nconst).orElseThrow();
    }

    @Override
    public PersonCore patch(String personId, PatchPersonRequest request) {
        int nconst = ImdbIds.parsePersonId(personId);
        PersonCore current = personRepository.findCore(nconst)
                .orElseThrow(() -> new NotFoundException("No person with id " + personId));
        handle(personRepository.updatePerson(nconst,
                request.primaryName() != null ? request.primaryName() : current.primaryName(),
                request.birthYear() != null ? request.birthYear() : current.birthYear(),
                request.deathYear() != null ? request.deathYear() : current.deathYear(),
                request.primaryProfession() != null ? request.primaryProfession() : current.primaryProfession(),
                request.version()), personId);
        return personRepository.findCore(nconst).orElseThrow();
    }

    @Override
    public void delete(String personId) {
        handle(personRepository.softDeletePerson(ImdbIds.parsePersonId(personId)), personId);
    }

    private static void handle(WriteResult result, String personId) {
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No person with id " + personId);
            case VERSION_CONFLICT -> throw new ConflictException(
                    "Person " + personId + " was modified by someone else - refresh and retry");
            case SUCCESS -> { }
        }
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=PersonAdminUseCaseImplTest`
Expected: PASS, 3 tests green.

- [ ] **Step 6: Write the failing controller test additions**

Add to `PersonControllerTest` (create the file with the full `@WebMvcTest(PersonController.class)`
structure if it doesn't exist yet, mirroring `TitleControllerTest`):

```java
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase personAdminUseCase;

    @Test
    void createPersonRequiresAdminRole() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/people")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("1").roles("USER"))
                        .contentType("application/json")
                        .content("""
                                {"primaryName":"New Person","primaryProfession":[]}
                                """))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }
```

- [ ] **Step 7: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=PersonControllerTest`
Expected: FAIL - `PersonController` has no `POST /api/v1/people` mapping yet.

- [ ] **Step 8: Add the admin endpoints to `PersonController`**

Add these imports: `com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase`,
`com.ludovictemgoua.imdb.application.CreatePersonRequest`, `UpdatePersonRequest`, `PatchPersonRequest`,
`com.ludovictemgoua.imdb.domain.model.PersonCore`, `jakarta.validation.Valid`,
`org.springframework.http.HttpStatus`, `org.springframework.security.access.prepost.PreAuthorize`,
`org.springframework.web.bind.annotation.{PostMapping,PutMapping,PatchMapping,DeleteMapping,RequestBody,ResponseStatus}`:

```java
    private final PersonAdminUseCase personAdminUseCase;

    public PersonController(SixDegreesUseCase sixDegreesUseCase, PersonAdminUseCase personAdminUseCase) {
        this.sixDegreesUseCase = sixDegreesUseCase;
        this.personAdminUseCase = personAdminUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public PersonCore create(@Valid @RequestBody CreatePersonRequest request) {
        return personAdminUseCase.create(request);
    }

    @PutMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PersonCore update(@PathVariable String personId, @Valid @RequestBody UpdatePersonRequest request) {
        return personAdminUseCase.update(personId, request);
    }

    @PatchMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PersonCore patch(@PathVariable String personId, @RequestBody PatchPersonRequest request) {
        return personAdminUseCase.patch(personId, request);
    }

    @DeleteMapping("/{personId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String personId) {
        personAdminUseCase.delete(personId);
    }
```

(Add `import org.springframework.web.bind.annotation.PathVariable;` too if not already present in this
file - it likely isn't, since the existing `sixDegrees` method takes only `@RequestParam`s.)

- [ ] **Step 9: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=PersonControllerTest`
Expected: PASS.

- [ ] **Step 10: Run the full suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/ src/main/java/com/ludovictemgoua/imdb/presentation/PersonController.java src/test/java/com/ludovictemgoua/imdb/application/PersonAdminUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/PersonControllerTest.java
git commit -m "Add admin CRUD for people (create/update/patch/delete)"
```

---

## Phase 5: Admin CRUD - Cast/Crew Credits (Principals)

`title_principals`' primary key is the composite `(tconst, ordering)` (`V0__base_schema.sql`) - no
surrogate id column is added in this phase. The `{principalId}` path segment in
`docs/crud-expansion-design.md` §5.4 maps directly to the `ordering` value, scoped under the existing
`{titleId}` path segment; no schema change needed beyond what `V7` already added.

### Task 5.1: `PrincipalCredit`, `TitleRepository` principal methods

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/PrincipalCredit.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/domain/repository/TitleRepository.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java`
- Modify: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `PrincipalCredit(String personId, String personName, String category, String job, List<String> characters, int ordering, int version)`. `TitleRepository.findAllPrincipals(int tconst) -> List<PrincipalCredit>` (uncapped, unlike `findTopCast`), `insertPrincipal(int tconst, int personId, String category, String job, List<String> characters, int ordering) -> WriteResult`, `updatePrincipal(int tconst, int ordering, String category, String job, List<String> characters, int expectedVersion) -> WriteResult`, `softDeletePrincipal(int tconst, int ordering) -> WriteResult`.

- [ ] **Step 1: Write the failing integration tests**

```java
    @Test
    void insertPrincipalThenFindAllPrincipalsIncludesIt() {
        var result = repository.insertPrincipal(100, 1, "actor", null, List.of("New Role"), 99);

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.SUCCESS);
        assertThat(repository.findAllPrincipals(100)).extracting("ordering").contains(99);
    }

    @Test
    void updatePrincipalBumpsVersionAndPersists() {
        repository.insertPrincipal(100, 1, "actor", null, List.of("Original"), 98);

        var result = repository.updatePrincipal(100, 98, "actor", null, List.of("Updated"), 0);

        assertThat(result).isEqualTo(com.ludovictemgoua.imdb.domain.repository.WriteResult.SUCCESS);
        var updated = repository.findAllPrincipals(100).stream()
                .filter(p -> p.ordering() == 98).findFirst().orElseThrow();
        assertThat(updated.characters()).containsExactly("Updated");
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void softDeletePrincipalExcludesItFromFindAllPrincipals() {
        repository.insertPrincipal(100, 1, "actor", null, List.of("Temp"), 97);

        repository.softDeletePrincipal(100, 97);

        assertThat(repository.findAllPrincipals(100)).extracting("ordering").doesNotContain(97);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest`
Expected: FAIL.

- [ ] **Step 3: Create `PrincipalCredit`**

```java
package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PrincipalCredit(String personId, String personName, String category, String job,
                               List<String> characters, int ordering, int version) {
}
```

- [ ] **Step 4: Add the new methods to `TitleRepository`**

```java
    List<PrincipalCredit> findAllPrincipals(int tconst);

    WriteResult insertPrincipal(int tconst, int personId, String category, String job,
                                List<String> characters, int ordering);

    WriteResult updatePrincipal(int tconst, int ordering, String category, String job,
                                List<String> characters, int expectedVersion);

    WriteResult softDeletePrincipal(int tconst, int ordering);
```

Add `import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;` to this file's imports.

- [ ] **Step 5: Implement the new methods in `JdbcTitleRepository`**

```java
    @Override
    public List<PrincipalCredit> findAllPrincipals(int tconst) {
        String sql = """
                SELECT tp.nconst, nb.primary_name, tp.category, tp.job, tp.characters, tp.ordering, tp.version
                FROM title_principals tp
                JOIN name_basics nb ON nb.nconst = tp.nconst
                WHERE tp.tconst = :tconst AND tp.deleted_at IS NULL
                ORDER BY tp.ordering
                """;
        return jdbc.query(sql, Map.of("tconst", tconst), JdbcTitleRepository::mapPrincipal);
    }

    @Override
    public WriteResult insertPrincipal(int tconst, int personId, String category, String job,
                                       List<String> characters, int ordering) {
        if (findCore(tconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                INSERT INTO title_principals (tconst, ordering, nconst, category, job, characters)
                VALUES (:tconst, :ordering, :nconst, :category, :job, :characters)
                """;
        var params = new MapSqlParameterSource()
                .addValue("tconst", tconst).addValue("ordering", ordering).addValue("nconst", personId)
                .addValue("category", category).addValue("job", job)
                .addValue("characters", characters.toArray(new String[0]), java.sql.Types.ARRAY, "text");
        jdbc.update(sql, params);
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult updatePrincipal(int tconst, int ordering, String category, String job,
                                       List<String> characters, int expectedVersion) {
        String sql = """
                UPDATE title_principals
                SET category = :category, job = :job, characters = :characters, version = version + 1
                WHERE tconst = :tconst AND ordering = :ordering AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("category", category).addValue("job", job)
                .addValue("characters", characters.toArray(new String[0]), java.sql.Types.ARRAY, "text")
                .addValue("tconst", tconst).addValue("ordering", ordering).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDeletePrincipal(int tconst, int ordering) {
        var params = new MapSqlParameterSource().addValue("tconst", tconst).addValue("ordering", ordering);
        int updated = jdbc.update(
                "UPDATE title_principals SET deleted_at = now() WHERE tconst = :tconst AND ordering = :ordering AND deleted_at IS NULL",
                params);
        return updated == 0 ? WriteResult.NOT_FOUND : WriteResult.SUCCESS;
    }

    private static PrincipalCredit mapPrincipal(ResultSet rs, int rowNum) throws SQLException {
        return new PrincipalCredit(ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                rs.getString("category"), rs.getString("job"), toStringList(rs.getArray("characters")),
                rs.getInt("ordering"), rs.getInt("version"));
    }
```

`updatePrincipal`'s missing existence pre-check (unlike the other write methods in this file) is
deliberate - `(tconst, ordering)` has no separate lookup method worth adding just for this, so a
version-mismatch-shaped `0` and a not-found-shaped `0` are indistinguishable here; both correctly surface
as `VERSION_CONFLICT` to the caller, which - for a composite-keyed row a caller must already know the
`ordering` of - is an acceptable simplification over adding a dedicated existence check.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcTitleRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/domain/model/PrincipalCredit.java src/main/java/com/ludovictemgoua/imdb/domain/repository/TitleRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcTitleRepositoryIntegrationTest.java
git commit -m "Add TitleRepository principal-credit write methods"
```

### Task 5.2: `TitleAdminUseCase` principal methods, `TitleController` endpoints, six-degrees cache eviction

**Files:**
- Modify: `src/main/java/com/ludovictemgoua/imdb/application/contracts/TitleAdminUseCase.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/PrincipalRequest.java` (record)
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingTitleAdminUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingPersonAdminUseCase.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/presentation/TitleController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImplTest.java` (extend)
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/TitleControllerTest.java` (extend)

**Interfaces:**
- Consumes: `TitleRepository` principal methods (Task 5.1), `PersonAdminUseCaseImpl` (Task 4.2)
- Produces: `TitleAdminUseCase.getAllPrincipals/addPrincipal/updatePrincipal/deletePrincipal` - `getAllPrincipals`
  is the one method on this interface **not** gated `@PreAuthorize` at the controller (it's the public
  uncapped-cast-list endpoint, §5.4). `CachingPersonAdminUseCase` - the `@Primary` decorator missing since
  Phase 4 - now evicts the `six-degrees` region (`allEntries`) on person update/delete, per
  `docs/crud-expansion-design.md` §6.2's "any people/principals write" rule.

- [ ] **Step 1: Write the failing unit test additions**

Add to `TitleAdminUseCaseImplTest`:

```java
    @Test
    void addPrincipalDelegatesToInsertPrincipal() {
        given(titleRepository.insertPrincipal(300, 1, "actor", null, List.of("Role"), 5))
                .willReturn(WriteResult.SUCCESS);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        useCase.addPrincipal("tt0000300", new PrincipalRequest("nm0000001", "actor", null, List.of("Role"), 5));
    }

    @Test
    void deletePrincipalThrowsNotFoundWhenMissing() {
        given(titleRepository.softDeletePrincipal(300, 5)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.deletePrincipal("tt0000300", 5)).isInstanceOf(NotFoundException.class);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleAdminUseCaseImplTest`
Expected: FAIL - the new interface methods don't exist yet.

- [ ] **Step 3: Create `PrincipalRequest` and add the methods to `TitleAdminUseCase`/`Impl`**

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record PrincipalRequest(@NotBlank String personId, @NotBlank String category, String job,
                                List<String> characters, int ordering) {
}
```

Add to `TitleAdminUseCase`:

```java
    java.util.List<com.ludovictemgoua.imdb.domain.model.PrincipalCredit> getAllPrincipals(String titleId);

    void addPrincipal(String titleId, PrincipalRequest request);

    void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion);

    void deletePrincipal(String titleId, int ordering);
```

Add to `TitleAdminUseCaseImpl`:

```java
    @Override
    public java.util.List<com.ludovictemgoua.imdb.domain.model.PrincipalCredit> getAllPrincipals(String titleId) {
        return titleRepository.findAllPrincipals(ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void addPrincipal(String titleId, PrincipalRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.insertPrincipal(tconst, ImdbIds.parsePersonId(request.personId()),
                request.category(), request.job(), request.characters(), request.ordering()), titleId);
    }

    @Override
    public void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.updatePrincipal(tconst, ordering, request.category(), request.job(),
                request.characters(), expectedVersion), titleId);
    }

    @Override
    public void deletePrincipal(String titleId, int ordering) {
        handle(titleRepository.softDeletePrincipal(ImdbIds.parseTitleId(titleId), ordering), titleId);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=TitleAdminUseCaseImplTest`
Expected: PASS.

- [ ] **Step 5: Add eviction methods to `CachingTitleAdminUseCase` and create `CachingPersonAdminUseCase`**

Add to `CachingTitleAdminUseCase` (import `PrincipalRequest`, `PrincipalCredit`, `List`):

```java
    @Override
    public List<PrincipalCredit> getAllPrincipals(String titleId) {
        return delegate.getAllPrincipals(titleId);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void addPrincipal(String titleId, PrincipalRequest request) {
        delegate.addPrincipal(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion) {
        delegate.updatePrincipal(titleId, ordering, request, expectedVersion);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void deletePrincipal(String titleId, int ordering) {
        delegate.deletePrincipal(titleId, ordering);
    }
```

Create `CachingPersonAdminUseCase`:

```java
package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.PersonAdminUseCaseImpl;
import com.ludovictemgoua.imdb.application.UpdatePersonRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// A renamed/removed person can change six-degrees path enrichment or its underlying graph edges -
// coarse full-region eviction on any update/delete, same trade-off as CachingTitleAdminUseCase's
// principal writes. create() needs no eviction: a brand-new person can't already be in any cached
// six-degrees result.
@Service
@Primary
public class CachingPersonAdminUseCase implements PersonAdminUseCase {

    private final PersonAdminUseCaseImpl delegate;

    public CachingPersonAdminUseCase(PersonAdminUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public PersonCore create(CreatePersonRequest request) {
        return delegate.create(request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public PersonCore update(String personId, UpdatePersonRequest request) {
        return delegate.update(personId, request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public PersonCore patch(String personId, PatchPersonRequest request) {
        return delegate.patch(personId, request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public void delete(String personId) {
        delegate.delete(personId);
    }
}
```

- [ ] **Step 6: Add the principal endpoints to `TitleController`**

```java
    @GetMapping("/{titleId}/principals")
    public List<PrincipalCredit> getAllPrincipals(@PathVariable String titleId) {
        return titleAdminUseCase.getAllPrincipals(titleId);
    }

    @PostMapping("/{titleId}/principals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public void addPrincipal(@PathVariable String titleId, @Valid @RequestBody PrincipalRequest request) {
        titleAdminUseCase.addPrincipal(titleId, request);
    }

    @PutMapping("/{titleId}/principals/{ordering}")
    @PreAuthorize("hasRole('ADMIN')")
    public void updatePrincipal(@PathVariable String titleId, @PathVariable int ordering,
                                @Valid @RequestBody PrincipalRequest request,
                                @RequestParam int expectedVersion) {
        titleAdminUseCase.updatePrincipal(titleId, ordering, request, expectedVersion);
    }

    @DeleteMapping("/{titleId}/principals/{ordering}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deletePrincipal(@PathVariable String titleId, @PathVariable int ordering) {
        titleAdminUseCase.deletePrincipal(titleId, ordering);
    }
```

Add `import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;` and
`import com.ludovictemgoua.imdb.application.PrincipalRequest;` to `TitleController.java`'s imports (all the
Spring annotation imports needed here were already added in Task 3.2's cleanup).

- [ ] **Step 7: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/ src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/ src/main/java/com/ludovictemgoua/imdb/presentation/TitleController.java src/test/java/com/ludovictemgoua/imdb/application/TitleAdminUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/TitleControllerTest.java
git commit -m "Add admin CRUD for principals and six-degrees cache eviction for people/principal writes"
```

### Task 5.3: Prove `@CacheEvict` actually reaches Redis (integration test)

**Files:**
- Create: `src/test/java/com/ludovictemgoua/imdb/infrastructure/cache/CacheEvictionIntegrationTest.java`

**Interfaces:** none new - this task only adds test coverage over `CachingTitleAdminUseCase` (Tasks 3.2/5.2)
and `CachingPersonAdminUseCase` (Task 5.2).

A mocked `CacheManager` in a unit test can prove a `@CacheEvict`-annotated method was *called*, but not
that it actually reached Redis - the same reasoning that motivated the four Redis-Testcontainers cache
integration tests already in this codebase (LLD §10.2) applies identically to eviction. This test primes
each of the three affected cache regions, performs the write that should evict them, then asserts the
specific previously-cached key is gone.

- [ ] **Step 1: Write the failing integration test**

```java
package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class CacheEvictionIntegrationTest {

    @Autowired
    TitleDetailUseCase titleDetailUseCase;
    @Autowired
    TopRatedUseCase topRatedUseCase;
    @Autowired
    TitleAdminUseCase titleAdminUseCase;
    @Autowired
    PersonAdminUseCase personAdminUseCase;
    @Autowired
    CacheManager cacheManager;

    @Test
    void updatingATitleEvictsItsTitleDetailCacheEntry() {
        titleDetailUseCase.getDetail("tt0000100");
        assertThat(cacheManager.getCache("title-detail").get("tt0000100")).isNotNull();

        var current = titleDetailUseCase.getDetail("tt0000100");
        titleAdminUseCase.update("tt0000100", new UpdateTitleRequest(
                current.primaryTitle(), current.originalTitle(), current.titleType(),
                current.startYear(), current.endYear(), current.runtimeMinutes(), current.genres(), 0));

        assertThat(cacheManager.getCache("title-detail").get("tt0000100")).isNull();
    }

    @Test
    void writingARatingEvictsTheEntireTopRatedRegion() {
        topRatedUseCase.findTopRated("Action", 10, 100);
        assertThat(cacheManager.getCache("top-rated").get("Action:10:100")).isNotNull();

        titleAdminUseCase.upsertRating("tt0000200", new RatingRequest(9.0, 200000));

        assertThat(cacheManager.getCache("top-rated").get("Action:10:100")).isNull();
    }

    @Test
    void updatingAPersonEvictsTheEntireSixDegreesRegion() {
        // Priming six-degrees requires a real SixDegreesUseCase call (personA=1, personB=2 per the
        // fixture graph, LLD §3) - autowire com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase
        // and call sixDegreesUseCase.compute("nm0000001", "nm0000002", 7) here, then assert
        // cacheManager.getCache("six-degrees").get("1-2") is not null (matching the "min-max" key
        // convention, LLD §6) before calling personAdminUseCase.patch(...) below and re-asserting null.
        personAdminUseCase.patch("nm0000001", new PatchPersonRequest("Kevin Bacon Jr.", null, null, List.of(), 0));

        assertThat(cacheManager.getCache("six-degrees").get("1-2")).isNull();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=CacheEvictionIntegrationTest`
Expected: initially FAIL only if the eviction wiring from Tasks 3.2/5.2 has any bug - if Tasks 3.2/5.2 were
implemented and verified correctly already, this test should pass immediately; treat a failure here as a
signal to revisit those tasks' `@CacheEvict`/`@Caching` annotations, not as expected red-then-green TDD
churn (this task is a **verification** of already-built behavior, not new production code).

- [ ] **Step 3: Fill in the `six-degrees` priming call from the comment above**, then re-run

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=CacheEvictionIntegrationTest`
Expected: PASS, all three tests green.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/ludovictemgoua/imdb/infrastructure/cache/CacheEvictionIntegrationTest.java
git commit -m "Add integration test proving cache eviction reaches real Redis for title/rating/person writes"
```

**Phase 5 checkpoint**: all admin CRUD from `docs/crud-expansion-design.md` §5 is complete and cache-safe,
now verified against real Redis rather than assumed correct from the annotations alone.
Phases 3-5 are independent of Phases 6-8 below (both depend only on Phase 1/2) - if executing with
subagents, these two groups can run in parallel.

---

## Phase 6: Watchlist

### Task 6.1: `Visibility`, `WatchlistView`/`WatchlistItemView`, `WatchlistRepository`

**Files:**
- Create: `src/main/resources/db/migration/V8__watchlists.sql`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/Visibility.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/WatchlistView.java`, `WatchlistItemView.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/repository/WatchlistRepository.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcWatchlistRepository.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcWatchlistRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: `CurrentUser` (already built in Task 1.6 - `WatchlistController`, Task 6.2, uses it)
- Produces: `Visibility.PUBLIC`/`PRIVATE` (reused by Reviews/Lists, Phases 7-8). `WatchlistView(int id, int userId, Visibility visibility, int version, List<WatchlistItemView> items)`, `WatchlistItemView(String titleId, String primaryTitle, Instant addedAt)`. `WatchlistRepository.findOrCreateByUserId(int userId) -> WatchlistView`, `findByUserId(int userId) -> Optional<WatchlistView>`, `addItem`/`removeItem(int watchlistId, int titleId) -> WriteResult`, `updateVisibility(int watchlistId, Visibility, int expectedVersion) -> WriteResult`.

- [ ] **Step 1: Write the failing test**

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcWatchlistRepositoryIntegrationTest {

    @Autowired
    JdbcWatchlistRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void findOrCreateByUserIdCreatesAnEmptyPrivateWatchlistOnFirstAccess() {
        int userId = userRepository.insert("watchlist-user@example.com", "hash", "User", Role.USER).id();

        var watchlist = repository.findOrCreateByUserId(userId);

        assertThat(watchlist.userId()).isEqualTo(userId);
        assertThat(watchlist.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(watchlist.items()).isEmpty();
    }

    @Test
    void findOrCreateByUserIdIsIdempotent() {
        int userId = userRepository.insert("watchlist-user2@example.com", "hash", "User", Role.USER).id();

        var first = repository.findOrCreateByUserId(userId);
        var second = repository.findOrCreateByUserId(userId);

        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test
    void addItemThenFindOrCreateIncludesIt() {
        int userId = userRepository.insert("watchlist-user3@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);

        repository.addItem(watchlist.id(), 100);

        assertThat(repository.findOrCreateByUserId(userId).items()).extracting("titleId").contains("tt0000100");
    }

    @Test
    void removeItemExcludesItFromTheWatchlist() {
        int userId = userRepository.insert("watchlist-user4@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);
        repository.addItem(watchlist.id(), 100);

        repository.removeItem(watchlist.id(), 100);

        assertThat(repository.findOrCreateByUserId(userId).items()).isEmpty();
    }

    @Test
    void updateVisibilityChangesItAndBumpsVersion() {
        int userId = userRepository.insert("watchlist-user5@example.com", "hash", "User", Role.USER).id();
        var watchlist = repository.findOrCreateByUserId(userId);

        var result = repository.updateVisibility(watchlist.id(), Visibility.PUBLIC, watchlist.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        assertThat(repository.findByUserId(userId).orElseThrow().visibility()).isEqualTo(Visibility.PUBLIC);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcWatchlistRepositoryIntegrationTest`
Expected: FAIL - none of these classes exist yet.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE watchlists (
    id         SERIAL PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users (id),
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    version    INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_watchlists_user_id ON watchlists (user_id) WHERE deleted_at IS NULL;

CREATE TABLE watchlist_items (
    watchlist_id INTEGER NOT NULL REFERENCES watchlists (id),
    title_id     INTEGER NOT NULL REFERENCES title_basics (tconst),
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, title_id)
);
```

- [ ] **Step 4: Create `Visibility`, `WatchlistView`, `WatchlistItemView`**

```java
package com.ludovictemgoua.imdb.domain.model;

public enum Visibility { PUBLIC, PRIVATE }
```

```java
package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record WatchlistItemView(String titleId, String primaryTitle, Instant addedAt) {
}
```

```java
package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record WatchlistView(int id, int userId, Visibility visibility, int version, List<WatchlistItemView> items) {
}
```

- [ ] **Step 5: Create `WatchlistRepository` and `JdbcWatchlistRepository`**

```java
package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;

import java.util.Optional;

public interface WatchlistRepository {

    WatchlistView findOrCreateByUserId(int userId);

    Optional<WatchlistView> findByUserId(int userId);

    WriteResult addItem(int watchlistId, int titleId);

    WriteResult removeItem(int watchlistId, int titleId);

    WriteResult updateVisibility(int watchlistId, Visibility visibility, int expectedVersion);
}
```

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistItemView;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcWatchlistRepository implements WatchlistRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWatchlistRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WatchlistView findOrCreateByUserId(int userId) {
        return findByUserId(userId).orElseGet(() -> create(userId));
    }

    @Override
    public Optional<WatchlistView> findByUserId(int userId) {
        String sql = "SELECT id, user_id, visibility, version FROM watchlists WHERE user_id = :userId AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("userId", userId), (rs, rowNum) -> new int[]{rs.getInt("id")})
                .stream().findFirst()
                .map(row -> hydrate(row[0], userId));
    }

    @Override
    public WriteResult addItem(int watchlistId, int titleId) {
        String sql = """
                INSERT INTO watchlist_items (watchlist_id, title_id) VALUES (:watchlistId, :titleId)
                ON CONFLICT DO NOTHING
                """;
        jdbc.update(sql, Map.of("watchlistId", watchlistId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult removeItem(int watchlistId, int titleId) {
        jdbc.update("DELETE FROM watchlist_items WHERE watchlist_id = :watchlistId AND title_id = :titleId",
                Map.of("watchlistId", watchlistId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult updateVisibility(int watchlistId, Visibility visibility, int expectedVersion) {
        String sql = """
                UPDATE watchlists SET visibility = :visibility, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("visibility", visibility.name()).addValue("id", watchlistId)
                .addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    private WatchlistView create(int userId) {
        String sql = "INSERT INTO watchlists (user_id) VALUES (:userId)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, new MapSqlParameterSource("userId", userId), keyHolder, new String[]{"id"});
        return new WatchlistView(keyHolder.getKey().intValue(), userId, Visibility.PRIVATE, 0, List.of());
    }

    private WatchlistView hydrate(int watchlistId, int userId) {
        String metaSql = "SELECT visibility, version FROM watchlists WHERE id = :id";
        var meta = jdbc.queryForMap(metaSql, Map.of("id", watchlistId));
        String itemsSql = """
                SELECT tb.tconst, tb.primary_title, wi.added_at
                FROM watchlist_items wi JOIN title_basics tb ON tb.tconst = wi.title_id
                WHERE wi.watchlist_id = :watchlistId AND tb.deleted_at IS NULL
                ORDER BY wi.added_at
                """;
        List<WatchlistItemView> items = jdbc.query(itemsSql, Map.of("watchlistId", watchlistId),
                (rs, rowNum) -> new WatchlistItemView(ImdbIds.formatTitleId(rs.getInt("tconst")),
                        rs.getString("primary_title"), rs.getTimestamp("added_at").toInstant()));
        return new WatchlistView(watchlistId, userId, Visibility.valueOf((String) meta.get("visibility")),
                ((Number) meta.get("version")).intValue(), items);
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcWatchlistRepositoryIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V8__watchlists.sql src/main/java/com/ludovictemgoua/imdb/domain/model/Visibility.java src/main/java/com/ludovictemgoua/imdb/domain/model/WatchlistView.java src/main/java/com/ludovictemgoua/imdb/domain/model/WatchlistItemView.java src/main/java/com/ludovictemgoua/imdb/domain/repository/WatchlistRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcWatchlistRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcWatchlistRepositoryIntegrationTest.java
git commit -m "Add watchlists/watchlist_items tables and WatchlistRepository"
```

### Task 6.2: `WatchlistUseCase`, `WatchlistController`

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/WatchlistUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/WatchlistUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/VisibilityRequest.java`, `AddWatchlistItemRequest.java` (records)
- Create: `src/main/java/com/ludovictemgoua/imdb/presentation/WatchlistController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/WatchlistUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/WatchlistControllerTest.java`

**Interfaces:**
- Consumes: `WatchlistRepository` (Task 6.1), `CurrentUser` (Task 1.6)
- Produces: `GET/PUT /api/v1/watchlist`, `POST/DELETE /api/v1/watchlist/items{,/{titleId}}`,
  `GET /api/v1/users/{userId}/watchlist` - the full watchlist endpoint set from
  `docs/crud-expansion-design.md` §4.2.

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WatchlistUseCaseImplTest {

    @Mock
    WatchlistRepository watchlistRepository;

    @Test
    void getOwnDelegatesToFindOrCreate() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findOrCreateByUserId(7)).willReturn(view);

        assertThat(new WatchlistUseCaseImpl(watchlistRepository).getOwn(7)).isSameAs(view);
    }

    @Test
    void getForUserReturnsThePublicWatchlistToAnyone() {
        var view = new WatchlistView(1, 7, Visibility.PUBLIC, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));

        var result = new WatchlistUseCaseImpl(watchlistRepository).getForUser(Optional.empty(), 7);

        assertThat(result).isSameAs(view);
    }

    @Test
    void getForUserThrowsNotFoundForAPrivateWatchlistViewedByAStranger() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));
        var useCase = new WatchlistUseCaseImpl(watchlistRepository);

        assertThatThrownBy(() -> useCase.getForUser(Optional.of(99), 7))
                .isInstanceOf(com.ludovictemgoua.imdb.domain.exception.NotFoundException.class);
    }

    @Test
    void getForUserAllowsTheOwnerToViewTheirOwnPrivateWatchlist() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));

        var result = new WatchlistUseCaseImpl(watchlistRepository).getForUser(Optional.of(7), 7);

        assertThat(result).isSameAs(view);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=WatchlistUseCaseImplTest`
Expected: FAIL - `WatchlistUseCaseImpl` doesn't exist yet.

- [ ] **Step 3: Create the request records and `WatchlistUseCase`/`Impl`**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotNull;

public record VisibilityRequest(@NotNull Visibility visibility) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record AddWatchlistItemRequest(@NotBlank String titleId) {
}
```

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;

import java.util.Optional;

public interface WatchlistUseCase {

    WatchlistView getOwn(int userId);

    WatchlistView getForUser(Optional<Integer> viewerUserId, int targetUserId);

    void addItem(int userId, String titleId);

    void removeItem(int userId, String titleId);

    void updateVisibility(int userId, Visibility visibility);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WatchlistUseCaseImpl implements WatchlistUseCase {

    private final WatchlistRepository watchlistRepository;

    public WatchlistUseCaseImpl(WatchlistRepository watchlistRepository) {
        this.watchlistRepository = watchlistRepository;
    }

    @Override
    public WatchlistView getOwn(int userId) {
        return watchlistRepository.findOrCreateByUserId(userId);
    }

    @Override
    public WatchlistView getForUser(Optional<Integer> viewerUserId, int targetUserId) {
        WatchlistView view = watchlistRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new NotFoundException("No watchlist for that user"));
        boolean isOwner = viewerUserId.isPresent() && viewerUserId.get() == targetUserId;
        if (view.visibility() == Visibility.PRIVATE && !isOwner) {
            throw new NotFoundException("No watchlist for that user");
        }
        return view;
    }

    @Override
    public void addItem(int userId, String titleId) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        watchlistRepository.addItem(watchlist.id(), ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void removeItem(int userId, String titleId) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        watchlistRepository.removeItem(watchlist.id(), ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void updateVisibility(int userId, Visibility visibility) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        var result = watchlistRepository.updateVisibility(watchlist.id(), visibility, watchlist.version());
        if (result == com.ludovictemgoua.imdb.domain.repository.WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Watchlist was modified concurrently - retry");
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=WatchlistUseCaseImplTest`
Expected: PASS, 4 tests green.

- [ ] **Step 5: Write the failing controller test**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchlistController.class)
class WatchlistControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    WatchlistUseCase watchlistUseCase;

    @Test
    void getOwnWatchlistRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOwnWatchlistReturnsItForAnAuthenticatedUser() throws Exception {
        given(watchlistUseCase.getOwn(7)).willReturn(new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of()));

        mockMvc.perform(get("/api/v1/watchlist").with(SecurityMockMvcRequestPostProcessors.user("7").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void getUserWatchlistIsAccessibleAnonymouslyWhenPublic() throws Exception {
        given(watchlistUseCase.getForUser(Optional.empty(), 7))
                .willReturn(new WatchlistView(1, 7, Visibility.PUBLIC, 0, List.of()));

        mockMvc.perform(get("/api/v1/users/7/watchlist"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=WatchlistControllerTest`
Expected: FAIL - `WatchlistController` doesn't exist yet.

- [ ] **Step 7: Create `WatchlistController`**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.AddWatchlistItemRequest;
import com.ludovictemgoua.imdb.application.VisibilityRequest;
import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WatchlistController {

    private final WatchlistUseCase watchlistUseCase;

    public WatchlistController(WatchlistUseCase watchlistUseCase) {
        this.watchlistUseCase = watchlistUseCase;
    }

    @GetMapping("/api/v1/watchlist")
    public WatchlistView getOwn(Authentication authentication) {
        return watchlistUseCase.getOwn(CurrentUser.requireId(authentication));
    }

    @PostMapping("/api/v1/watchlist/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addItem(Authentication authentication, @Valid @RequestBody AddWatchlistItemRequest request) {
        watchlistUseCase.addItem(CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/api/v1/watchlist/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(Authentication authentication, @PathVariable String titleId) {
        watchlistUseCase.removeItem(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/watchlist/visibility")
    public void updateVisibility(Authentication authentication, @Valid @RequestBody VisibilityRequest request) {
        watchlistUseCase.updateVisibility(CurrentUser.requireId(authentication), request.visibility());
    }

    @GetMapping("/api/v1/users/{userId}/watchlist")
    public WatchlistView getForUser(Authentication authentication, @PathVariable int userId) {
        return watchlistUseCase.getForUser(CurrentUser.idOf(authentication), userId);
    }
}
```

`getOwn`/`addItem`/`removeItem`/`updateVisibility` are **not** in the security filter chain's `permitAll()`
list (Task 1.3), so Spring Security already rejects an anonymous request with `401` before this
controller ever runs, verified by `getOwnWatchlistRequiresAuthentication` above -
`CurrentUser.requireId`'s `IllegalStateException` for a missing user is an internal-consistency
safety net, not the primary 401 mechanism.

- [ ] **Step 8: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=WatchlistControllerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 9: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/contracts/WatchlistUseCase.java src/main/java/com/ludovictemgoua/imdb/application/WatchlistUseCaseImpl.java src/main/java/com/ludovictemgoua/imdb/application/VisibilityRequest.java src/main/java/com/ludovictemgoua/imdb/application/AddWatchlistItemRequest.java src/main/java/com/ludovictemgoua/imdb/presentation/WatchlistController.java src/test/java/com/ludovictemgoua/imdb/application/WatchlistUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/WatchlistControllerTest.java
git commit -m "Add WatchlistUseCase and WatchlistController"
```

**Phase 6 checkpoint**: watchlists are fully live. This is the template Phase 8 (Custom Lists) follows
closely (public/private visibility, ownership checks, 404-not-403 for private resources).

---

## Phase 7: Reviews

### Task 7.1: `Review`/`RatingAggregate`, `ReviewRepository`

**Files:**
- Create: `src/main/resources/db/migration/V9__reviews.sql`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/Review.java`, `RatingAggregate.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/repository/ReviewRepository.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcReviewRepository.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcReviewRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `Review(int id, int userId, int titleId, int rating, String body, int version, Instant createdAt, Instant updatedAt)`, `RatingAggregate(double average, int count)`. `ReviewRepository.insert`, `findByUserAndTitle`, `update`, `softDelete`, `findByTitle`/`findByUser` (paged), `aggregateForTitle(int titleId) -> RatingAggregate` (average `0.0`/count `0` when no reviews exist).

- [ ] **Step 1: Write the failing integration test**

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcReviewRepositoryIntegrationTest {

    @Autowired
    JdbcReviewRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void insertThenFindByUserAndTitleRoundTrips() {
        int userId = userRepository.insert("reviewer1@example.com", "hash", "Reviewer", Role.USER).id();

        var review = repository.insert(userId, 100, 9, "Great film");

        var found = repository.findByUserAndTitle(userId, 100).orElseThrow();
        assertThat(found.id()).isEqualTo(review.id());
        assertThat(found.rating()).isEqualTo(9);
        assertThat(found.version()).isEqualTo(0);
    }

    @Test
    void updateBumpsVersionAndPersists() {
        int userId = userRepository.insert("reviewer2@example.com", "hash", "Reviewer", Role.USER).id();
        var review = repository.insert(userId, 100, 5, "Meh");

        var result = repository.update(review.id(), 8, "Actually great", review.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findByUserAndTitle(userId, 100).orElseThrow();
        assertThat(updated.rating()).isEqualTo(8);
        assertThat(updated.version()).isEqualTo(1);
    }

    @Test
    void softDeleteExcludesItFromFindByUserAndTitle() {
        int userId = userRepository.insert("reviewer3@example.com", "hash", "Reviewer", Role.USER).id();
        var review = repository.insert(userId, 100, 5, "Meh");

        repository.softDelete(review.id(), review.version());

        assertThat(repository.findByUserAndTitle(userId, 100)).isEmpty();
    }

    @Test
    void aggregateForTitleAveragesAcrossReviewers() {
        int user1 = userRepository.insert("reviewer4@example.com", "hash", "R4", Role.USER).id();
        int user2 = userRepository.insert("reviewer5@example.com", "hash", "R5", Role.USER).id();
        repository.insert(user1, 200, 10, null);
        repository.insert(user2, 200, 6, null);

        var aggregate = repository.aggregateForTitle(200);

        assertThat(aggregate.count()).isEqualTo(2);
        assertThat(aggregate.average()).isCloseTo(8.0, within(0.01));
    }

    @Test
    void aggregateForTitleIsZeroWhenNoReviewsExist() {
        var aggregate = repository.aggregateForTitle(999999);

        assertThat(aggregate.count()).isEqualTo(0);
        assertThat(aggregate.average()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcReviewRepositoryIntegrationTest`
Expected: FAIL.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE reviews (
    id         SERIAL PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users (id),
    title_id   INTEGER NOT NULL REFERENCES title_basics (tconst),
    rating     INTEGER NOT NULL CHECK (rating BETWEEN 1 AND 10),
    body       TEXT,
    version    INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_reviews_user_title ON reviews (user_id, title_id) WHERE deleted_at IS NULL;
```

- [ ] **Step 4: Create `Review`/`RatingAggregate`**

```java
package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record Review(int id, int userId, int titleId, int rating, String body, int version,
                      Instant createdAt, Instant updatedAt) {
}
```

```java
package com.ludovictemgoua.imdb.domain.model;

public record RatingAggregate(double average, int count) {
}
```

- [ ] **Step 5: Create `ReviewRepository` and `JdbcReviewRepository`**

```java
package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.RatingAggregate;
import com.ludovictemgoua.imdb.domain.model.Review;

import java.util.Optional;

public interface ReviewRepository {

    Review insert(int userId, int titleId, int rating, String body);

    Optional<Review> findByUserAndTitle(int userId, int titleId);

    WriteResult update(int reviewId, int rating, String body, int expectedVersion);

    WriteResult softDelete(int reviewId, int expectedVersion);

    PagedResult<Review> findByTitle(int titleId, int page, int size);

    PagedResult<Review> findByUser(int userId, int page, int size);

    RatingAggregate aggregateForTitle(int titleId);
}
```

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.RatingAggregate;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcReviewRepository implements ReviewRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReviewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Review insert(int userId, int titleId, int rating, String body) {
        String sql = """
                INSERT INTO reviews (user_id, title_id, rating, body) VALUES (:userId, :titleId, :rating, :body)
                """;
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("titleId", titleId)
                .addValue("rating", rating).addValue("body", body);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        return findByUserAndTitle(userId, titleId).orElseThrow();
    }

    @Override
    public Optional<Review> findByUserAndTitle(int userId, int titleId) {
        String sql = """
                SELECT * FROM reviews WHERE user_id = :userId AND title_id = :titleId AND deleted_at IS NULL
                """;
        return jdbc.query(sql, Map.of("userId", userId, "titleId", titleId), JdbcReviewRepository::mapReview)
                .stream().findFirst();
    }

    @Override
    public WriteResult update(int reviewId, int rating, String body, int expectedVersion) {
        String sql = """
                UPDATE reviews SET rating = :rating, body = :body, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("rating", rating).addValue("body", body)
                .addValue("id", reviewId).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDelete(int reviewId, int expectedVersion) {
        String sql = """
                UPDATE reviews SET deleted_at = now() WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = Map.of("id", reviewId, "expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public PagedResult<Review> findByTitle(int titleId, int page, int size) {
        String dataSql = """
                SELECT * FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("titleId", titleId).addValue("limit", size).addValue("offset", (long) page * size);
        List<Review> content = jdbc.query(dataSql, params, JdbcReviewRepository::mapReview);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public PagedResult<Review> findByUser(int userId, int page, int size) {
        String dataSql = """
                SELECT * FROM reviews WHERE user_id = :userId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM reviews WHERE user_id = :userId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", size).addValue("offset", (long) page * size);
        List<Review> content = jdbc.query(dataSql, params, JdbcReviewRepository::mapReview);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public RatingAggregate aggregateForTitle(int titleId) {
        String sql = """
                SELECT COALESCE(AVG(rating), 0) AS avg_rating, COUNT(*) AS review_count
                FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL
                """;
        return jdbc.queryForObject(sql, Map.of("titleId", titleId), (rs, rowNum) ->
                new RatingAggregate(rs.getDouble("avg_rating"), rs.getInt("review_count")));
    }

    private static Review mapReview(ResultSet rs, int rowNum) throws SQLException {
        return new Review(rs.getInt("id"), rs.getInt("user_id"), rs.getInt("title_id"), rs.getInt("rating"),
                rs.getString("body"), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcReviewRepositoryIntegrationTest`
Expected: PASS, 5 tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V9__reviews.sql src/main/java/com/ludovictemgoua/imdb/domain/model/Review.java src/main/java/com/ludovictemgoua/imdb/domain/model/RatingAggregate.java src/main/java/com/ludovictemgoua/imdb/domain/repository/ReviewRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcReviewRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcReviewRepositoryIntegrationTest.java
git commit -m "Add reviews table and ReviewRepository"
```

### Task 7.2: `ReviewUseCase`, `ReviewController`, `TitleDetail.userRating*` wiring, cache eviction

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/ReviewUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/ReviewUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/ReviewRequest.java` (record)
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingReviewUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/presentation/ReviewController.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/domain/model/TitleDetail.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/application/TitleDetailUseCaseImpl.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/ReviewUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/TitleDetailUseCaseImplTest.java` (extend)
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/ReviewControllerTest.java`

**Interfaces:**
- Consumes: `ReviewRepository` (Task 7.1), `CurrentUser` (Task 1.6)
- Produces: the full reviews endpoint set (`docs/crud-expansion-design.md` §4.3), and
  `TitleDetail.userRatingAverage()`/`userRatingCount()` populated from `ReviewRepository.aggregateForTitle`.

- [ ] **Step 1: Write the failing unit tests**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReviewUseCaseImplTest {

    @Mock
    ReviewRepository reviewRepository;

    @Test
    void createThrowsConflictWhenAReviewAlreadyExists() {
        given(reviewRepository.findByUserAndTitle(7, 100)).willReturn(
                Optional.of(new Review(1, 7, 100, 8, "Existing", 0, Instant.now(), Instant.now())));
        var useCase = new ReviewUseCaseImpl(reviewRepository);

        assertThatThrownBy(() -> useCase.create(7, "tt0000100", new ReviewRequest(9, "New", 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createInsertsWhenNoneExistsYet() {
        given(reviewRepository.findByUserAndTitle(7, 100)).willReturn(Optional.empty());
        var created = new Review(1, 7, 100, 9, "New", 0, Instant.now(), Instant.now());
        given(reviewRepository.insert(7, 100, 9, "New")).willReturn(created);

        var result = new ReviewUseCaseImpl(reviewRepository).create(7, "tt0000100", new ReviewRequest(9, "New", 0));

        assertThat(result.rating()).isEqualTo(9);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ReviewUseCaseImplTest`
Expected: FAIL.

- [ ] **Step 3: Create `ReviewRequest`, `ReviewUseCase`/`Impl`**

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReviewRequest(@Min(1) @Max(10) int rating, String body, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;

public interface ReviewUseCase {

    Review create(int userId, String titleId, ReviewRequest request);

    Review getMine(int userId, String titleId);

    Review update(int userId, String titleId, ReviewRequest request);

    void delete(int userId, String titleId, int expectedVersion);

    PagedResult<Review> listForTitle(String titleId, int page, int size);

    PagedResult<Review> listForUser(int userId, int page, int size);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class ReviewUseCaseImpl implements ReviewUseCase {

    private final ReviewRepository reviewRepository;

    public ReviewUseCaseImpl(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Override
    public Review create(int userId, String titleId, ReviewRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        if (reviewRepository.findByUserAndTitle(userId, tconst).isPresent()) {
            throw new ConflictException("You already reviewed this title - use PUT to update it");
        }
        return reviewRepository.insert(userId, tconst, request.rating(), request.body());
    }

    @Override
    public Review getMine(int userId, String titleId) {
        return reviewRepository.findByUserAndTitle(userId, ImdbIds.parseTitleId(titleId))
                .orElseThrow(() -> new NotFoundException("You haven't reviewed this title"));
    }

    @Override
    public Review update(int userId, String titleId, ReviewRequest request) {
        Review existing = getMine(userId, titleId);
        WriteResult result = reviewRepository.update(existing.id(), request.rating(), request.body(), request.version());
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Your review was modified concurrently - refresh and retry");
        }
        return getMine(userId, titleId);
    }

    @Override
    public void delete(int userId, String titleId, int expectedVersion) {
        Review existing = getMine(userId, titleId);
        WriteResult result = reviewRepository.softDelete(existing.id(), expectedVersion);
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Your review was modified concurrently - refresh and retry");
        }
    }

    @Override
    public PagedResult<Review> listForTitle(String titleId, int page, int size) {
        return reviewRepository.findByTitle(ImdbIds.parseTitleId(titleId), page, size);
    }

    @Override
    public PagedResult<Review> listForUser(int userId, int page, int size) {
        return reviewRepository.findByUser(userId, page, size);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ReviewUseCaseImplTest`
Expected: PASS.

- [ ] **Step 5: Add `userRatingAverage`/`userRatingCount` to `TitleDetail` and wire them in `TitleDetailUseCaseImpl`**

`TitleDetail` gains two components at the end:

```java
package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record TitleDetail(String id, String primaryTitle, String originalTitle, String titleType,
                           Integer startYear, Integer endYear, Integer runtimeMinutes,
                           List<String> genres, RatingView rating,
                           List<CreditedPerson> directors, List<CreditedPerson> writers,
                           List<CastMember> cast, int castTotalCount,
                           double userRatingAverage, int userRatingCount) {
}
```

`TitleDetailUseCaseImpl` gains a `ReviewRepository` dependency and populates the two new fields:

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.RatingView;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class TitleDetailUseCaseImpl implements TitleDetailUseCase {

    private static final int CAST_LIMIT = 20;

    private final TitleRepository titleRepository;
    private final ReviewRepository reviewRepository;

    public TitleDetailUseCaseImpl(TitleRepository titleRepository, ReviewRepository reviewRepository) {
        this.titleRepository = titleRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public TitleDetail getDetail(String titleId) {
        int tconst = ImdbIds.parseTitleId(titleId);
        var core = titleRepository.findCore(tconst)
                .orElseThrow(() -> new NotFoundException("No title with id " + titleId));
        var directors = titleRepository.findDirectors(tconst);
        var writers = titleRepository.findWriters(tconst);
        var cast = titleRepository.findTopCast(tconst, CAST_LIMIT);
        int castTotal = titleRepository.countCast(tconst);
        var userRating = reviewRepository.aggregateForTitle(tconst);
        return new TitleDetail(
                core.id(), core.primaryTitle(), core.originalTitle(), core.titleType(),
                core.startYear(), core.endYear(), core.runtimeMinutes(), core.genres(),
                new RatingView(core.averageRating() == null ? 0 : core.averageRating(),
                        core.numVotes() == null ? 0 : core.numVotes()),
                directors, writers, cast, castTotal,
                userRating.average(), userRating.count());
    }
}
```

Update the existing `TitleDetailUseCaseImplTest` (if one exists; if not, this is the first test for this
class - either way, the existing four-argument `TitleDetail` assertions elsewhere in the test suite need
a `ReviewRepository` mock added and a stubbed `aggregateForTitle` call). Add to that test class:

```java
    @Mock
    ReviewRepository reviewRepository;

    // In every existing test method that constructs `new TitleDetailUseCaseImpl(titleRepository)`,
    // change the constructor call to `new TitleDetailUseCaseImpl(titleRepository, reviewRepository)`
    // and stub `given(reviewRepository.aggregateForTitle(100)).willReturn(new RatingAggregate(0, 0));`
    // (or the appropriate tconst or a lenient stub) before each call, matching whatever fixture tconst
    // that test already uses.
```

- [ ] **Step 6: Run the full unit suite to catch every other place `TitleDetail`'s constructor is called**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test`
Expected: FAIL initially with compile errors everywhere `new TitleDetail(...)` or
`new TitleDetailUseCaseImpl(...)` is called with the old arity - fix each call site the compiler reports
(likely `TitleControllerTest` and any fixture-building test helper) by adding the two new arguments
(`0.0, 0` for a plain stub, or real aggregate values where the test cares). Re-run until green.

- [ ] **Step 7: Create the cache-evicting decorator and `ReviewController`**

```java
package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.application.ReviewUseCaseImpl;
import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// title-detail embeds userRatingAverage/userRatingCount (Step 5 above) - any review write must evict
// the affected title's cache entry the same way an admin rating write does (CachingTitleAdminUseCase).
@Service
@Primary
public class CachingReviewUseCase implements ReviewUseCase {

    private final ReviewUseCaseImpl delegate;

    public CachingReviewUseCase(ReviewUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public Review create(int userId, String titleId, ReviewRequest request) {
        return delegate.create(userId, titleId, request);
    }

    @Override
    public Review getMine(int userId, String titleId) {
        return delegate.getMine(userId, titleId);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public Review update(int userId, String titleId, ReviewRequest request) {
        return delegate.update(userId, titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void delete(int userId, String titleId, int expectedVersion) {
        delegate.delete(userId, titleId, expectedVersion);
    }

    @Override
    public PagedResult<Review> listForTitle(String titleId, int page, int size) {
        return delegate.listForTitle(titleId, page, size);
    }

    @Override
    public PagedResult<Review> listForUser(int userId, int page, int size) {
        return delegate.listForUser(userId, page, size);
    }
}
```

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class ReviewController {

    private final ReviewUseCase reviewUseCase;

    public ReviewController(ReviewUseCase reviewUseCase) {
        this.reviewUseCase = reviewUseCase;
    }

    @PostMapping("/api/v1/titles/{titleId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Review create(Authentication authentication, @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.create(CurrentUser.requireId(authentication), titleId, request);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews")
    public PagedResult<Review> listForTitle(
            @PathVariable String titleId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForTitle(titleId, page, size);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews/me")
    public Review getMine(Authentication authentication, @PathVariable String titleId) {
        return reviewUseCase.getMine(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/titles/{titleId}/reviews/me")
    public Review update(Authentication authentication, @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.update(CurrentUser.requireId(authentication), titleId, request);
    }

    @DeleteMapping("/api/v1/titles/{titleId}/reviews/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String titleId, @RequestParam int expectedVersion) {
        reviewUseCase.delete(CurrentUser.requireId(authentication), titleId, expectedVersion);
    }

    @GetMapping("/api/v1/users/{userId}/reviews")
    public PagedResult<Review> listForUser(
            @PathVariable int userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForUser(userId, page, size);
    }
}
```

Add `GET /api/v1/titles/*/reviews` (note: **not** `/reviews/me`, which needs auth) and
`GET /api/v1/users/*/reviews` to the security filter chain's `permitAll()` GET list (Task 1.3) if either
was missed there - `/api/v1/users/*/reviews` is already listed; add
`"/api/v1/titles/*/reviews"` alongside it now (`/api/v1/titles/**` already covers this as a prefix match,
so no change is actually needed - confirm this by testing `listForTitleIsPubliclyAccessible` below before
assuming so).

- [ ] **Step 8: Write and run the controller test**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
class ReviewControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ReviewUseCase reviewUseCase;

    @Test
    void listForTitleIsPubliclyAccessible() throws Exception {
        given(reviewUseCase.listForTitle("tt0000100", 0, 20)).willReturn(new PagedResult<>(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/titles/tt0000100/reviews"))
                .andExpect(status().isOk());
    }

    @Test
    void getMineRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/titles/tt0000100/reviews/me"))
                .andExpect(status().isUnauthorized());
    }
}
```

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ReviewControllerTest`
Expected: PASS. If `listForTitleIsPubliclyAccessible` instead returns `401`, add
`"/api/v1/titles/**"` is already present as a GET-permitted prefix (Task 1.3) - the likely real cause is
`/reviews/me` also incorrectly matching that same prefix and being wrongly public; if so, order Spring
Security's matchers so the more specific `/reviews/me` pattern is declared **before** the broader
`/api/v1/titles/**` pattern (Spring Security evaluates `authorizeHttpRequests` matchers in declaration
order, first match wins), moving `"/api/v1/titles/*/reviews/me"` is not itself a GET-permitted pattern
today so this should not occur - if it does, it means the broad `/api/v1/titles/**` permit is matching
`/me` unintentionally, and the fix is adding an explicit
`.requestMatchers(HttpMethod.GET, "/api/v1/titles/*/reviews/me").authenticated()` **above** the broader
permit rule in `SecurityConfig`.

- [ ] **Step 9: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/contracts/ReviewUseCase.java src/main/java/com/ludovictemgoua/imdb/application/ReviewUseCaseImpl.java src/main/java/com/ludovictemgoua/imdb/application/ReviewRequest.java src/main/java/com/ludovictemgoua/imdb/infrastructure/cache/CachingReviewUseCase.java src/main/java/com/ludovictemgoua/imdb/presentation/ReviewController.java src/main/java/com/ludovictemgoua/imdb/domain/model/TitleDetail.java src/main/java/com/ludovictemgoua/imdb/application/TitleDetailUseCaseImpl.java src/test/
git commit -m "Add ReviewUseCase/ReviewController and wire user ratings into title detail"
```

**Phase 7 checkpoint**: reviews are fully live, and title detail now shows both the original IMDb rating
and the aggregate user rating side by side.

---

## Phase 8: Custom Lists

### Task 8.1: `CustomList`/`CustomListView`/`ListItemView`, `CustomListRepository`

**Files:**
- Create: `src/main/resources/db/migration/V10__lists.sql`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/model/CustomList.java`, `CustomListView.java`, `ListItemView.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/domain/repository/CustomListRepository.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcCustomListRepository.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcCustomListRepositoryIntegrationTest.java`

**Interfaces:**
- Produces: `CustomList(int id, int userId, String name, Visibility visibility, int version)`,
  `ListItemView(String titleId, String primaryTitle, Instant addedAt)`,
  `CustomListView(int id, int userId, String name, Visibility visibility, int version, List<ListItemView> items)`.
  `CustomListRepository.insert(int userId, String name, Visibility) -> CustomList`,
  `findById(int listId) -> Optional<CustomListView>`,
  `update(int listId, String name, Visibility, int expectedVersion) -> WriteResult`,
  `softDelete(int listId, int expectedVersion) -> WriteResult`,
  `findByUser(int userId, int page, int size) -> PagedResult<CustomList>`,
  `findPublic(int page, int size) -> PagedResult<CustomList>`,
  `addItem(int listId, int titleId) -> WriteResult`, `removeItem(int listId, int titleId) -> WriteResult`.

- [ ] **Step 1: Write the failing integration test**

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@Sql("/fixtures/fixture-data.sql")
class JdbcCustomListRepositoryIntegrationTest {

    @Autowired
    JdbcCustomListRepository repository;
    @Autowired
    UserRepository userRepository;

    @Test
    void insertThenFindByIdRoundTrips() {
        int userId = userRepository.insert("lister1@example.com", "hash", "Lister", Role.USER).id();

        var created = repository.insert(userId, "Best of 2024", Visibility.PRIVATE);

        var found = repository.findById(created.id()).orElseThrow();
        assertThat(found.name()).isEqualTo("Best of 2024");
        assertThat(found.items()).isEmpty();
    }

    @Test
    void addItemThenFindByIdIncludesIt() {
        int userId = userRepository.insert("lister2@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Watch Later", Visibility.PUBLIC);

        repository.addItem(created.id(), 100);

        assertThat(repository.findById(created.id()).orElseThrow().items())
                .extracting("titleId").contains("tt0000100");
    }

    @Test
    void removeItemExcludesItFromTheList() {
        int userId = userRepository.insert("lister3@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Watch Later", Visibility.PUBLIC);
        repository.addItem(created.id(), 100);

        repository.removeItem(created.id(), 100);

        assertThat(repository.findById(created.id()).orElseThrow().items()).isEmpty();
    }

    @Test
    void updateRenamesAndBumpsVersion() {
        int userId = userRepository.insert("lister4@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Old Name", Visibility.PRIVATE);

        var result = repository.update(created.id(), "New Name", Visibility.PUBLIC, created.version());

        assertThat(result).isEqualTo(WriteResult.SUCCESS);
        var updated = repository.findById(created.id()).orElseThrow();
        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.visibility()).isEqualTo(Visibility.PUBLIC);
    }

    @Test
    void softDeleteExcludesItFromFindById() {
        int userId = userRepository.insert("lister5@example.com", "hash", "Lister", Role.USER).id();
        var created = repository.insert(userId, "Delete Me", Visibility.PRIVATE);

        repository.softDelete(created.id(), created.version());

        assertThat(repository.findById(created.id())).isEmpty();
    }

    @Test
    void findPublicOnlyReturnsPublicLists() {
        int userId = userRepository.insert("lister6@example.com", "hash", "Lister", Role.USER).id();
        repository.insert(userId, "Public List", Visibility.PUBLIC);
        repository.insert(userId, "Private List", Visibility.PRIVATE);

        var publicLists = repository.findPublic(0, 20);

        assertThat(publicLists.content()).extracting("name").contains("Public List").doesNotContain("Private List");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcCustomListRepositoryIntegrationTest`
Expected: FAIL.

- [ ] **Step 3: Create the migration**

```sql
CREATE TABLE lists (
    id         SERIAL PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users (id),
    name       TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    version    INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE list_items (
    list_id  INTEGER NOT NULL REFERENCES lists (id),
    title_id INTEGER NOT NULL REFERENCES title_basics (tconst),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ordering SERIAL,
    PRIMARY KEY (list_id, title_id)
);
```

- [ ] **Step 4: Create the domain models**

```java
package com.ludovictemgoua.imdb.domain.model;

public record CustomList(int id, int userId, String name, Visibility visibility, int version) {
}
```

```java
package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record ListItemView(String titleId, String primaryTitle, Instant addedAt) {
}
```

```java
package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record CustomListView(int id, int userId, String name, Visibility visibility, int version,
                              List<ListItemView> items) {
}
```

- [ ] **Step 5: Create `CustomListRepository` and `JdbcCustomListRepository`**

```java
package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;

import java.util.Optional;

public interface CustomListRepository {

    CustomList insert(int userId, String name, Visibility visibility);

    Optional<CustomListView> findById(int listId);

    WriteResult update(int listId, String name, Visibility visibility, int expectedVersion);

    WriteResult softDelete(int listId, int expectedVersion);

    PagedResult<CustomList> findByUser(int userId, int page, int size);

    PagedResult<CustomList> findPublic(int page, int size);

    WriteResult addItem(int listId, int titleId);

    WriteResult removeItem(int listId, int titleId);
}
```

```java
package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.ListItemView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcCustomListRepository implements CustomListRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCustomListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CustomList insert(int userId, String name, Visibility visibility) {
        String sql = "INSERT INTO lists (user_id, name, visibility) VALUES (:userId, :name, :visibility)";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("name", name).addValue("visibility", visibility.name());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        return new CustomList(keyHolder.getKey().intValue(), userId, name, visibility, 0);
    }

    @Override
    public Optional<CustomListView> findById(int listId) {
        String sql = "SELECT * FROM lists WHERE id = :id AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("id", listId), (rs, rowNum) -> rs.getInt("user_id"))
                .stream().findFirst()
                .flatMap(ownerId -> hydrate(listId));
    }

    @Override
    public WriteResult update(int listId, String name, Visibility visibility, int expectedVersion) {
        String sql = """
                UPDATE lists SET name = :name, visibility = :visibility, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("name", name).addValue("visibility", visibility.name())
                .addValue("id", listId).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDelete(int listId, int expectedVersion) {
        String sql = "UPDATE lists SET deleted_at = now() WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL";
        var params = Map.of("id", listId, "expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public PagedResult<CustomList> findByUser(int userId, int page, int size) {
        String dataSql = """
                SELECT * FROM lists WHERE user_id = :userId AND deleted_at IS NULL
                ORDER BY id LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM lists WHERE user_id = :userId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", size).addValue("offset", (long) page * size);
        List<CustomList> content = jdbc.query(dataSql, params, JdbcCustomListRepository::mapList);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public PagedResult<CustomList> findPublic(int page, int size) {
        String dataSql = """
                SELECT * FROM lists WHERE visibility = 'PUBLIC' AND deleted_at IS NULL
                ORDER BY id LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM lists WHERE visibility = 'PUBLIC' AND deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        List<CustomList> content = jdbc.query(dataSql, params, JdbcCustomListRepository::mapList);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public WriteResult addItem(int listId, int titleId) {
        String sql = "INSERT INTO list_items (list_id, title_id) VALUES (:listId, :titleId) ON CONFLICT DO NOTHING";
        jdbc.update(sql, Map.of("listId", listId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult removeItem(int listId, int titleId) {
        jdbc.update("DELETE FROM list_items WHERE list_id = :listId AND title_id = :titleId",
                Map.of("listId", listId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    private Optional<CustomListView> hydrate(int listId) {
        String metaSql = "SELECT * FROM lists WHERE id = :id AND deleted_at IS NULL";
        List<CustomList> meta = jdbc.query(metaSql, Map.of("id", listId), JdbcCustomListRepository::mapList);
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        String itemsSql = """
                SELECT tb.tconst, tb.primary_title, li.added_at
                FROM list_items li JOIN title_basics tb ON tb.tconst = li.title_id
                WHERE li.list_id = :listId AND tb.deleted_at IS NULL
                ORDER BY li.ordering
                """;
        List<ListItemView> items = jdbc.query(itemsSql, Map.of("listId", listId),
                (rs, rowNum) -> new ListItemView(ImdbIds.formatTitleId(rs.getInt("tconst")),
                        rs.getString("primary_title"), rs.getTimestamp("added_at").toInstant()));
        CustomList list = meta.get(0);
        return Optional.of(new CustomListView(list.id(), list.userId(), list.name(), list.visibility(),
                list.version(), items));
    }

    private static CustomList mapList(ResultSet rs, int rowNum) throws SQLException {
        return new CustomList(rs.getInt("id"), rs.getInt("user_id"), rs.getString("name"),
                Visibility.valueOf(rs.getString("visibility")), rs.getInt("version"));
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=JdbcCustomListRepositoryIntegrationTest`
Expected: PASS, 6 tests green.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V10__lists.sql src/main/java/com/ludovictemgoua/imdb/domain/model/CustomList.java src/main/java/com/ludovictemgoua/imdb/domain/model/CustomListView.java src/main/java/com/ludovictemgoua/imdb/domain/model/ListItemView.java src/main/java/com/ludovictemgoua/imdb/domain/repository/CustomListRepository.java src/main/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcCustomListRepository.java src/test/java/com/ludovictemgoua/imdb/infrastructure/persistence/JdbcCustomListRepositoryIntegrationTest.java
git commit -m "Add lists/list_items tables and CustomListRepository"
```

### Task 8.2: `ListUseCase` (ownership/visibility rules), `ListController`

**Files:**
- Create: `src/main/java/com/ludovictemgoua/imdb/application/contracts/ListUseCase.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/ListUseCaseImpl.java`
- Create: `src/main/java/com/ludovictemgoua/imdb/application/CreateListRequest.java`, `UpdateListRequest.java`, `AddListItemRequest.java` (records)
- Create: `src/main/java/com/ludovictemgoua/imdb/presentation/ListController.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/application/ListUseCaseImplTest.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/presentation/ListControllerTest.java`

**Interfaces:**
- Consumes: `CustomListRepository` (Task 8.1), `CurrentUser` (Task 1.6)
- Produces: the full custom-lists endpoint set (`docs/crud-expansion-design.md` §4.4), with the exact
  privacy rule stated there: viewing a `PRIVATE` list you don't own is `404`; *writing* to a list you
  don't own is `403` if it's `PUBLIC` (its existence is already visible) and `404` if it's `PRIVATE`
  (existence stays hidden either way).

- [ ] **Step 1: Write the failing unit test**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ListUseCaseImplTest {

    @Mock
    CustomListRepository customListRepository;

    @Test
    void getByIdReturnsAPublicListToAnyone() {
        var view = new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));

        assertThat(new ListUseCaseImpl(customListRepository).getById(1, Optional.empty())).isSameAs(view);
    }

    @Test
    void getByIdThrowsNotFoundForAPrivateListViewedByAStranger() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.getById(1, Optional.of(99))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addItemThrowsForbiddenWhenAStrangerWritesToAPublicList() {
        var view = new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.addItem(1, 99, "tt0000100")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addItemThrowsNotFoundWhenAStrangerWritesToAPrivateList() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.addItem(1, 99, "tt0000100")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addItemSucceedsForTheOwner() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));

        new ListUseCaseImpl(customListRepository).addItem(1, 7, "tt0000100");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ListUseCaseImplTest`
Expected: FAIL.

- [ ] **Step 3: Create the request records and `ListUseCase`/`Impl`**

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateListRequest(@NotBlank String name, @NotNull Visibility visibility) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateListRequest(@NotBlank String name, @NotNull Visibility visibility, int version) {
}
```

```java
package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record AddListItemRequest(@NotBlank String titleId) {
}
```

```java
package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreateListRequest;
import com.ludovictemgoua.imdb.application.UpdateListRequest;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;

import java.util.Optional;

public interface ListUseCase {

    CustomList create(int userId, CreateListRequest request);

    CustomListView getById(int listId, Optional<Integer> viewerUserId);

    PagedResult<CustomList> getMine(int userId, int page, int size);

    PagedResult<CustomList> getPublic(int page, int size);

    void update(int listId, int userId, UpdateListRequest request);

    void delete(int listId, int userId, int expectedVersion);

    void addItem(int listId, int userId, String titleId);

    void removeItem(int listId, int userId, String titleId);
}
```

```java
package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ListUseCaseImpl implements ListUseCase {

    private final CustomListRepository customListRepository;

    public ListUseCaseImpl(CustomListRepository customListRepository) {
        this.customListRepository = customListRepository;
    }

    @Override
    public CustomList create(int userId, CreateListRequest request) {
        return customListRepository.insert(userId, request.name(), request.visibility());
    }

    @Override
    public CustomListView getById(int listId, Optional<Integer> viewerUserId) {
        CustomListView list = findOrThrow(listId);
        boolean isOwner = viewerUserId.isPresent() && viewerUserId.get() == list.userId();
        if (list.visibility() == Visibility.PRIVATE && !isOwner) {
            throw new NotFoundException("No list with id " + listId);
        }
        return list;
    }

    @Override
    public PagedResult<CustomList> getMine(int userId, int page, int size) {
        return customListRepository.findByUser(userId, page, size);
    }

    @Override
    public PagedResult<CustomList> getPublic(int page, int size) {
        return customListRepository.findPublic(page, size);
    }

    @Override
    public void update(int listId, int userId, UpdateListRequest request) {
        CustomListView list = requireOwner(listId, userId);
        WriteResult result = customListRepository.update(listId, request.name(), request.visibility(), request.version());
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("List " + list.id() + " was modified concurrently - refresh and retry");
        }
    }

    @Override
    public void delete(int listId, int userId, int expectedVersion) {
        requireOwner(listId, userId);
        WriteResult result = customListRepository.softDelete(listId, expectedVersion);
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("List " + listId + " was modified concurrently - refresh and retry");
        }
    }

    @Override
    public void addItem(int listId, int userId, String titleId) {
        requireOwner(listId, userId);
        customListRepository.addItem(listId, ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void removeItem(int listId, int userId, String titleId) {
        requireOwner(listId, userId);
        customListRepository.removeItem(listId, ImdbIds.parseTitleId(titleId));
    }

    private CustomListView findOrThrow(int listId) {
        return customListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("No list with id " + listId));
    }

    // A non-owner writing to a PRIVATE list gets 404 (existence hidden, same as a read); a non-owner
    // writing to a PUBLIC list gets 403 (existence is already visible, the action is what's denied).
    // docs/crud-expansion-design.md §4.4.
    private CustomListView requireOwner(int listId, int userId) {
        CustomListView list = findOrThrow(listId);
        if (list.userId() == userId) {
            return list;
        }
        if (list.visibility() == Visibility.PRIVATE) {
            throw new NotFoundException("No list with id " + listId);
        }
        throw new ForbiddenException("You do not own list " + listId);
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ListUseCaseImplTest`
Expected: PASS, 5 tests green.

- [ ] **Step 5: Write the failing controller test**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ListController.class)
class ListControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ListUseCase listUseCase;

    @Test
    void getPublicListsIsAccessibleAnonymously() throws Exception {
        given(listUseCase.getPublic(0, 20)).willReturn(new com.ludovictemgoua.imdb.domain.model.PagedResult<>(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/lists/public"))
                .andExpect(status().isOk());
    }

    @Test
    void getByIdIsAccessibleAnonymouslyForAPublicList() throws Exception {
        given(listUseCase.getById(1, Optional.empty()))
                .willReturn(new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of()));

        mockMvc.perform(get("/api/v1/lists/1"))
                .andExpect(status().isOk());
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/lists")
                        .contentType("application/json")
                        .content("""
                                {"name":"My List","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 6: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ListControllerTest`
Expected: FAIL - `ListController` doesn't exist yet.

- [ ] **Step 7: Create `ListController`**

```java
package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.AddListItemRequest;
import com.ludovictemgoua.imdb.application.CreateListRequest;
import com.ludovictemgoua.imdb.application.UpdateListRequest;
import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lists")
@Validated
public class ListController {

    private final ListUseCase listUseCase;

    public ListController(ListUseCase listUseCase) {
        this.listUseCase = listUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomList create(Authentication authentication, @Valid @RequestBody CreateListRequest request) {
        return listUseCase.create(CurrentUser.requireId(authentication), request);
    }

    @GetMapping("/me")
    public PagedResult<CustomList> getMine(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getMine(CurrentUser.requireId(authentication), page, size);
    }

    @GetMapping("/public")
    public PagedResult<CustomList> getPublic(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getPublic(page, size);
    }

    @GetMapping("/{listId}")
    public CustomListView getById(Authentication authentication, @PathVariable int listId) {
        return listUseCase.getById(listId, CurrentUser.idOf(authentication));
    }

    @PutMapping("/{listId}")
    public void update(Authentication authentication, @PathVariable int listId,
                       @Valid @RequestBody UpdateListRequest request) {
        listUseCase.update(listId, CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable int listId, @RequestParam int expectedVersion) {
        listUseCase.delete(listId, CurrentUser.requireId(authentication), expectedVersion);
    }

    @PostMapping("/{listId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addItem(Authentication authentication, @PathVariable int listId,
                       @Valid @RequestBody AddListItemRequest request) {
        listUseCase.addItem(listId, CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/{listId}/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(Authentication authentication, @PathVariable int listId, @PathVariable String titleId) {
        listUseCase.removeItem(listId, CurrentUser.requireId(authentication), titleId);
    }
}
```

Add `import org.springframework.web.bind.annotation.RequestMapping;` to the imports above (used by the
class-level `@RequestMapping("/api/v1/lists")` annotation).

`GET /api/v1/lists/public` and `GET /api/v1/lists/{listId}` (i.e. `/api/v1/lists/*`) must be in the
security filter chain's `permitAll()` GET list (Task 1.3) - both already are; `GET /api/v1/lists/me`
must **not** be public (it's already excluded, since `/api/v1/lists/*` with a single path segment does not
match the two-segment... actually `/api/v1/lists/*` DOES match `/api/v1/lists/me` as a single-segment
wildcard. Fix this now: change `SecurityConfig`'s pattern from `"/api/v1/lists/*"` to a request-matcher
that excludes `me` - simplest fix is to list `/api/v1/lists/me` explicitly under `.authenticated()` by
declaring it **before** the broader permit rule (Spring evaluates matchers in order, first match wins):

```java
                        .requestMatchers(HttpMethod.GET, "/api/v1/lists/me").authenticated()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/titles/**", "/api/v1/genres/**", "/api/v1/people/six-degrees",
                                "/api/v1/lists/public", "/api/v1/lists/*", "/api/v1/users/*",
                                "/api/v1/users/*/watchlist", "/api/v1/users/*/reviews").permitAll()
```

(Move the new `/api/v1/lists/me` line to sit above the existing broad-permit block in `SecurityConfig`.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ListControllerTest`
Expected: PASS, 3 tests green.

- [ ] **Step 9: Add a `getMineRequiresAuthentication` regression test**

Add to `ListControllerTest`:

```java
    @Test
    void getMineRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/lists/me"))
                .andExpect(status().isUnauthorized());
    }
```

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test -Dtest=ListControllerTest`
Expected: PASS - this is exactly the matcher-ordering fix from Step 7 being verified.

- [ ] **Step 10: Run the full unit and integration suites**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test && JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/ludovictemgoua/imdb/application/contracts/ListUseCase.java src/main/java/com/ludovictemgoua/imdb/application/ListUseCaseImpl.java src/main/java/com/ludovictemgoua/imdb/application/CreateListRequest.java src/main/java/com/ludovictemgoua/imdb/application/UpdateListRequest.java src/main/java/com/ludovictemgoua/imdb/application/AddListItemRequest.java src/main/java/com/ludovictemgoua/imdb/presentation/ListController.java src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java src/test/java/com/ludovictemgoua/imdb/application/ListUseCaseImplTest.java src/test/java/com/ludovictemgoua/imdb/presentation/ListControllerTest.java
git commit -m "Add ListUseCase/ListController with ownership and visibility rules"
```

**Phase 8 checkpoint**: all four new user-generated resources (users/watchlist/reviews/lists) and all
admin CRUD are complete. Only E2E contract tests and a final full-suite pass remain.

---

## Phase 9: E2E/Postman Additions and Final Verification

### Task 9.1: `docker-compose.e2e.yaml` env vars for auth

**Files:**
- Modify: `docker-compose.e2e.yaml`

**Interfaces:**
- Produces: the e2e stack's `imdb-service` boots with a valid `JWT_SECRET` and a bootstrap admin, so the
  Postman collection's admin-gated requests (Task 9.2) have real ADMIN credentials to authenticate with.

- [ ] **Step 1: Add the env vars to the `imdb-service` block in `docker-compose.e2e.yaml`**

```yaml
      JWT_SECRET: "e2e-test-only-secret-not-for-real-deployments-32bytes-plus"
      IMDB_BOOTSTRAP_ADMIN_EMAIL: "admin@imdb.local"
      IMDB_BOOTSTRAP_ADMIN_PASSWORD: "e2e-test-admin-password"
```

- [ ] **Step 2: Bring up the e2e stack locally and confirm the bootstrap admin can log in**

Run:
```bash
docker compose -f docker-compose.e2e.yaml -p imdb-e2e up -d --build
# wait for imdb-service to report healthy (curl -sf http://localhost:8080/actuator/health), then:
curl -s -X POST http://localhost:8080/api/v1/auth/login -H "Content-Type: application/json" \
  -d '{"email":"admin@imdb.local","password":"e2e-test-admin-password"}'
```
Expected: a JSON body with a real `accessToken`/`refreshToken`, not an error.

- [ ] **Step 3: Tear down**

Run: `docker compose -f docker-compose.e2e.yaml -p imdb-e2e down -v`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.e2e.yaml
git commit -m "Add JWT/bootstrap-admin env vars to the e2e stack"
```

### Task 9.2: Postman/Newman collection additions

**Files:**
- Modify: `postman/imdb-e2e.postman_collection.json`

**Interfaces:**
- Produces: new collection items covering the full auth flow, one full CRUD lifecycle for each new
  resource, and the three negative cases called out in `docs/crud-expansion-design.md` §9 (403 for a
  non-admin admin-write attempt, 409 for a stale-version update, 404 for a private list a stranger
  requests). Chained via Postman's collection-level variables (`{{accessToken}}` etc., set in each
  request's Tests script from the previous response) - the existing collection's `variable` array already
  has `baseUrl`; add `accessToken`, `adminAccessToken`, `createdTitleId` alongside it, all with an empty
  starting `value`.

- [ ] **Step 1: Add auth-flow items** (append to the collection's top-level `item` array, after the
  existing nine items)

```json
{
  "name": "Register a new user",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/auth/register",
    "header": [{"key": "Content-Type", "value": "application/json"}],
    "body": {
      "mode": "raw",
      "raw": "{\"email\":\"e2e-user@example.com\",\"password\":\"password123\",\"displayName\":\"E2E User\"}"
    }
  },
  "event": [{
    "listen": "test",
    "script": {"exec": [
      "pm.test('status is 201', () => pm.response.to.have.status(201));",
      "const body = pm.response.json();",
      "pm.collectionVariables.set('accessToken', body.accessToken);",
      "pm.collectionVariables.set('refreshToken', body.refreshToken);"
    ]}
  }]
},
{
  "name": "Admin logs in",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/auth/login",
    "header": [{"key": "Content-Type", "value": "application/json"}],
    "body": {
      "mode": "raw",
      "raw": "{\"email\":\"admin@imdb.local\",\"password\":\"e2e-test-admin-password\"}"
    }
  },
  "event": [{
    "listen": "test",
    "script": {"exec": [
      "pm.test('status is 200', () => pm.response.to.have.status(200));",
      "pm.collectionVariables.set('adminAccessToken', pm.response.json().accessToken);"
    ]}
  }]
},
{
  "name": "Refresh the access token",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/auth/refresh",
    "header": [{"key": "Content-Type", "value": "application/json"}],
    "body": {"mode": "raw", "raw": "{\"refreshToken\":\"{{refreshToken}}\"}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 200', () => pm.response.to.have.status(200));"]}
  }]
}
```

- [ ] **Step 2: Add admin CRUD + negative-case items**

```json
{
  "name": "Non-admin cannot create a title",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/titles",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{accessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"primaryTitle\":\"Nope\",\"originalTitle\":\"Nope\",\"titleType\":\"movie\",\"genres\":[]}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 403', () => pm.response.to.have.status(403));"]}
  }]
},
{
  "name": "Admin creates a title",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/titles",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{adminAccessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"primaryTitle\":\"E2E Test Movie\",\"originalTitle\":\"E2E Test Movie\",\"titleType\":\"movie\",\"startYear\":2024,\"genres\":[\"Drama\"]}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": [
      "pm.test('status is 201', () => pm.response.to.have.status(201));",
      "pm.collectionVariables.set('createdTitleId', pm.response.json().id);"
    ]}
  }]
},
{
  "name": "Stale-version update returns 409",
  "request": {
    "method": "PUT",
    "url": "{{baseUrl}}/api/v1/titles/{{createdTitleId}}",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{adminAccessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"primaryTitle\":\"Renamed\",\"originalTitle\":\"Renamed\",\"titleType\":\"movie\",\"startYear\":2024,\"genres\":[],\"version\":99}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 409', () => pm.response.to.have.status(409));"]}
  }]
}
```

- [ ] **Step 3: Add watchlist/review/list lifecycle items**

```json
{
  "name": "Add the new title to the watchlist",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/watchlist/items",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{accessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"titleId\":\"{{createdTitleId}}\"}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 201', () => pm.response.to.have.status(201));"]}
  }]
},
{
  "name": "Review the new title",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/titles/{{createdTitleId}}/reviews",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{accessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"rating\":9,\"body\":\"E2E-tested and great\",\"version\":0}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 201', () => pm.response.to.have.status(201));"]}
  }]
},
{
  "name": "Create a private list",
  "request": {
    "method": "POST",
    "url": "{{baseUrl}}/api/v1/lists",
    "header": [
      {"key": "Content-Type", "value": "application/json"},
      {"key": "Authorization", "value": "Bearer {{accessToken}}"}
    ],
    "body": {"mode": "raw", "raw": "{\"name\":\"E2E Private List\",\"visibility\":\"PRIVATE\"}"}
  },
  "event": [{
    "listen": "test",
    "script": {"exec": [
      "pm.test('status is 201', () => pm.response.to.have.status(201));",
      "pm.collectionVariables.set('createdListId', pm.response.json().id);"
    ]}
  }]
},
{
  "name": "A stranger cannot view the private list",
  "request": {
    "method": "GET",
    "url": "{{baseUrl}}/api/v1/lists/{{createdListId}}",
    "header": [{"key": "Authorization", "value": "Bearer {{adminAccessToken}}"}]
  },
  "event": [{
    "listen": "test",
    "script": {"exec": ["pm.test('status is 404', () => pm.response.to.have.status(404));"]}
  }]
}
```

- [ ] **Step 4: Add the three new empty-string collection variables**

Add to the collection's top-level `variable` array (alongside the existing `baseUrl` entry):

```json
{"key": "accessToken", "value": ""},
{"key": "refreshToken", "value": ""},
{"key": "adminAccessToken", "value": ""},
{"key": "createdTitleId", "value": ""},
{"key": "createdListId", "value": ""}
```

- [ ] **Step 5: Validate the JSON and run it against a live e2e stack**

Run (PowerShell, avoiding the pyenv `python3` shim issue noted earlier this project):
```powershell
Get-Content postman/imdb-e2e.postman_collection.json -Raw | ConvertFrom-Json | Out-Null
```
Expected: no error (valid JSON). Then, with the e2e stack from Task 9.1 still up:
```bash
npx --yes newman run postman/imdb-e2e.postman_collection.json --env-var baseUrl=http://localhost:8080
```
Expected: all assertions pass, including the new ones (403/409/404 cases and the full auth/watchlist/
review/list lifecycle).

- [ ] **Step 6: Tear down and commit**

```bash
docker compose -f docker-compose.e2e.yaml -p imdb-e2e down -v
git add postman/imdb-e2e.postman_collection.json
git commit -m "Add auth, admin-CRUD, and user-content e2e contract tests to the Postman collection"
```

### Task 9.3: Final full-suite verification and documentation updates

**Files:**
- Modify: `imdb/docs/low-level-design.md`
- Modify: `imdb/README.md`

**Interfaces:** none - this task only verifies and documents; no new production code.

- [ ] **Step 1: Run the complete local verification sequence**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test
JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify
```
Expected: PASS for every unit and integration test across all nine phases (including every existing test
from before this plan - none should have been weakened or deleted to make this pass).

- [ ] **Step 2: Run the full `imdb-ci.yml` sequence locally** (mirrors the `e2e` CI job exactly)

```bash
docker compose -f docker-compose.e2e.yaml -p imdb-e2e up -d --build
# poll /actuator/health, then poll the seed container's exit code (see imdb-ci.yml's own steps)
npx --yes newman run postman/imdb-e2e.postman_collection.json --env-var baseUrl=http://localhost:8080
docker compose -f docker-compose.e2e.yaml -p imdb-e2e down -v
```
Expected: PASS, matching what CI will do on push.

- [ ] **Step 3: Update `docs/low-level-design.md`**

Add a new `§12. CRUD Expansion` section (or renumber to insert it before the existing `§11. Open Items`,
whichever reads better once you're looking at the live document) summarizing: the two new layers, the
auth mechanism, the `version`/`deleted_at` convention now present on every writable table, the cache
eviction rules per region, and a pointer to `docs/crud-expansion-design.md` for full rationale - mirroring
how §7.1 was written for the earlier dashboard-fix work (a real, verified summary, not a restatement of
the design doc).

- [ ] **Step 4: Update `README.md`**

Extend the **API** section with the new endpoints (or a pointer to the design doc's §4/§5 tables rather
than duplicating them in full), add **Authentication** as a new top-level section describing the JWT
register/login/refresh flow and the bootstrap-admin mechanism, and add a note under **Known limitations**
if Step 1/2 surfaced anything not already called out in `docs/crud-expansion-design.md` §11.

- [ ] **Step 5: Commit**

```bash
git add docs/low-level-design.md README.md
git commit -m "Document the CRUD expansion in the LLD and README"
```

**Phase 9 checkpoint - and plan complete**: every endpoint in `docs/crud-expansion-design.md` is
implemented, tested at all three tiers (unit/integration/e2e), and documented.

