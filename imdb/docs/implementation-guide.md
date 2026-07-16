# IMDb Copycat API - Implementation Guide

| | |
|---|---|
| Author | Ludovic Temgoua Abanda |
| Status | Draft |
| Date | 2026-07-05 |
| Related docs | `low-level-design.md` (the design these classes implement), `product-design.md` |

## How to use this document

This is a typing companion, not a code drop - every class below is meant to be typed into your IDE
yourself so the patterns actually stick, not pasted. The order matters: each step only depends on
previous steps, so if you type them in order, the project should compile (and often run) at every
checkpoint rather than only at the very end.

Where a pattern repeats (four nearly-identical DTOs, three controllers with the same shape), full code is
given once and the repeats are described rather than spelled out again - filling those in yourself is
where the pattern actually gets internalized, not where it gets lost.

A few corrections surfaced while working through this that aren't yet reflected anywhere except here and
the LLD edits made alongside it - notably: Spring Boot 4.1's OTLP tracing property is
`management.opentelemetry.tracing.export.otlp.endpoint`, not the Boot-3-era `management.otlp.tracing.*`;
`springdoc-openapi` and `datasource-micrometer` aren't yet available for Boot 4.1 (both dropped, see LLD
§11); and a `V0` Flyway migration was added to create the base tables that only `abanda/imdb-postgresql`
normally provides (LLD §3.4), since a fresh Testcontainers Postgres has none of them.

---

## Step 0: Fix the generated scaffolding

Two small edits to what Spring Initializr already generated, before writing anything new.

**`src/test/java/com/ludovictemgoua/imdb/TestcontainersConfiguration.java`** - pin Postgres to match
production (`abanda/imdb-postgresql` runs Postgres 17):

```java
@Bean
@ServiceConnection
PostgreSQLContainer postgresContainer() {
    return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
}
```

Leave the `LgtmStackContainer` and Redis beans as generated - they're genuinely useful for
`TestImdbApplication`'s local dev-run convenience (full tracing/metrics without needing
`docker-compose up`). Flyway will run automatically against whichever Postgres connection is active
(the Testcontainers one here, or `docker-compose.yaml`'s real one at runtime) - that's what makes `V0`
(next step) necessary.

**`src/main/resources/application.yaml`** - replace the generated one-liner with:

```yaml
spring:
  application:
    name: imdb
  datasource:
    url: jdbc:postgresql://localhost:5432/imdb
    username: imdb
    password: password
  data:
    redis:
      host: localhost
      port: 6379
  flyway:
    enabled: true

management:
  endpoints:
    web:
      exposure:
        include: health, prometheus
  tracing:
    sampling:
      probability: 1.0
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: http://localhost:4318/v1/traces

six-degrees:
  side-cap: 4
  absolute-max-degree: 7
  fan-out-cap: 200
  query-timeout-seconds: 2

top-rated:
  default-min-votes: 1000
```

The `spring.datasource`/`data.redis` values here are the `localhost` defaults for running the app
directly against `docker-compose up`'s exposed ports; `docker-compose.yaml`'s `imdb-service` environment
variables (already in place) override these to the in-network hostnames (`postgres`, `redis`, `tempo`)
when the app itself runs as a compose service instead. The `six-degrees.*` and `top-rated.*` blocks are
our own custom properties (LLD §5.2, §11) - Spring Boot automatically makes any `key: value` under a
namespace bindable via `@ConfigurationProperties`, which the services below use instead of hardcoding
these numbers.

**Checkpoint**: `./mvnw compile` should still succeed (nothing new to compile yet, just config).

---

## Step 1: Database migrations

Three files under `src/main/resources/db/migration/`. Flyway orders by version number, not filename
creation order, so `V0` runs first even though it's documented last in the LLD.

- `V0__base_schema.sql` - copy verbatim from LLD §3.4.
- `V1__extensions_and_search_indexes.sql` - copy verbatim from LLD §3.2 (note the `genres` index is on
  the *expression* `(genres::text[])`, not the bare column - LLD §3.2 explains why).
- `V2__co_star_edges_materialized_view.sql` - copy verbatim from LLD §3.3.

**Checkpoint**: run `TestImdbApplication.main()` (or `./mvnw spring-boot:test-run`). It should start
cleanly, spinning up Postgres/Redis/Grafana-LGTM Testcontainers and applying all three migrations. Check
the logs for `Successfully applied 3 migrations`.

---

## Step 2: `ImdbIds` - the id-translation utility

`src/main/java/com/ludovictemgoua/imdb/ImdbIds.java` (root package - both the `web` and `repository`
layers need it, so it doesn't belong to either):

```java
package com.ludovictemgoua.imdb;

public final class ImdbIds {

    private ImdbIds() {}

    public static int parseTitleId(String tt) {
        return Integer.parseInt(requirePrefix(tt, "tt"));
    }

    public static int parsePersonId(String nm) {
        return Integer.parseInt(requirePrefix(nm, "nm"));
    }

    public static String formatTitleId(int tconst) {
        return "tt" + pad7(tconst);
    }

    public static String formatPersonId(int nconst) {
        return "nm" + pad7(nconst);
    }

    private static String requirePrefix(String id, String prefix) {
        if (id == null || !id.startsWith(prefix) || id.length() <= prefix.length()) {
            throw new IllegalArgumentException("Expected an id starting with '" + prefix + "': " + id);
        }
        return id.substring(prefix.length());
    }

    private static String pad7(int value) {
        return String.format("%07d", value);
    }
}
```

`IllegalArgumentException` is deliberate here, not a custom exception type - `ApiExceptionHandler` (Step
5) maps it straight to a 400, and there's no other behavior anyone would ever want from a malformed id.

---

## Step 3: DTOs

All records, all in `src/main/java/com/ludovictemgoua/imdb/web/dto/`. One file per record, matching this
monorepo's `votee` convention.

```java
public record TitleSummary(String id, String primaryTitle, String originalTitle, String titleType,
                            Integer startYear, Integer endYear) {}

public record RatingView(double average, int numVotes) {}

public record CreditedPerson(String id, String name) {}

public record CastMember(String id, String name, String category, java.util.List<String> characters,
                          int ordering) {}

public record TitleDetail(String id, String primaryTitle, String originalTitle, String titleType,
                           Integer startYear, Integer endYear, Integer runtimeMinutes,
                           java.util.List<String> genres, RatingView rating,
                           java.util.List<CreditedPerson> directors, java.util.List<CreditedPerson> writers,
                           java.util.List<CastMember> cast, int castTotalCount) {}

public record GenreTopRatedItem(String id, String primaryTitle, Integer startYear, double averageRating,
                                 int numVotes, double weightedRating) {}

public record PersonRef(String id, String name) {}

public record SharedTitle(String id, String primaryTitle) {}

public record PathStep(String id, String name, SharedTitle sharedTitle) {}

public record SixDegreesResult(PersonRef personA, PersonRef personB, Integer degree,
                                boolean withinRequestedMax, java.util.List<PathStep> path) {}

public record PersonCandidate(String id, String name, Integer birthYear, java.util.List<String> knownFor) {}

public record PagedResult<T>(java.util.List<T> content, long totalElements, int page, int size) {}
```

Two things worth noticing rather than just typing past:

- `SixDegreesResult.degree` is `Integer`, not `int` - `null` means "no connection exists within the
  absolute 7-degree cap at all," which is a different thing from `withinRequestedMax=false` (a connection
  exists, just beyond what the caller asked for).
- `PagedResult<T>` replaces the `Page<TitleSummary>` the LLD described in prose (§4.1) - `org.springframework.data.domain.Page`
  is a Spring Data type, and §3.1 already decided against pulling in Spring Data's abstractions for a
  plain-JDBC codebase. It also sidesteps a real gotcha: Jackson cannot cleanly deserialize into the `Page`
  *interface* on a Redis cache read (it needs a concrete, directly-instantiable type), which our own
  simple record has no trouble with.

Use fully-qualified `java.util.List` inline above only to keep this guide's code blocks self-contained -
in your actual files, add a normal `import java.util.List;` and drop the prefix, same for every DTO.

---

## Step 4: Repositories

`src/main/java/com/ludovictemgoua/imdb/repository/`. This is where `NamedParameterJdbcTemplate` (already
autoconfigured by `spring-boot-starter-jdbc` - no config class needed for it) does all the work.

### `TitleRepository`

```java
package com.ludovictemgoua.imdb.repository;

import com.ludovictemgoua.imdb.ImdbIds;
import com.ludovictemgoua.imdb.web.dto.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TitleRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TitleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PagedResult<TitleSummary> search(String query, int page, int size) {
        String dataSql = """
                SELECT tconst, primary_title, original_title, title_type, start_year, end_year
                FROM title_basics
                WHERE primary_title % :query OR original_title % :query
                ORDER BY similarity(primary_title, :query) DESC
                LIMIT :limit OFFSET :offset
                """;
        String countSql = """
                SELECT count(*) FROM title_basics
                WHERE primary_title % :query OR original_title % :query
                """;
        var params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<TitleSummary> content = jdbc.query(dataSql, params, TitleRepository::mapSummary);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    public Optional<TitleCore> findCore(int tconst) {
        String sql = """
                SELECT tb.tconst, tb.primary_title, tb.original_title, tb.title_type,
                       tb.start_year, tb.end_year, tb.runtime_minutes, tb.genres,
                       tr.average_rating, tr.num_votes
                FROM title_basics tb
                LEFT JOIN title_ratings tr ON tr.tconst = tb.tconst
                WHERE tb.tconst = :tconst
                """;
        return jdbc.query(sql, Map.of("tconst", tconst), TitleRepository::mapCore).stream().findFirst();
    }

    public List<CreditedPerson> findDirectors(int tconst) {
        return findCrew(tconst, "directors");
    }

    public List<CreditedPerson> findWriters(int tconst) {
        return findCrew(tconst, "writers");
    }

    public List<CastMember> findTopCast(int tconst, int limit) {
        String sql = """
                SELECT tp.nconst, nb.primary_name, tp.category, tp.characters, tp.ordering
                FROM title_principals tp
                JOIN name_basics nb ON nb.nconst = tp.nconst
                WHERE tp.tconst = :tconst
                ORDER BY tp.ordering
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("tconst", tconst).addValue("limit", limit);
        return jdbc.query(sql, params, TitleRepository::mapCastMember);
    }

    public int countCast(int tconst) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM title_principals WHERE tconst = :tconst",
                Map.of("tconst", tconst), Integer.class);
        return count == null ? 0 : count;
    }

    public List<GenreTopRatedItem> findTopRated(String genre, int limit, int minVotes) {
        String sql = """
                WITH pool AS (
                    SELECT tb.tconst, tb.primary_title, tb.start_year, tr.average_rating, tr.num_votes
                    FROM title_basics tb
                    JOIN title_ratings tr ON tr.tconst = tb.tconst
                    WHERE tb.title_type = 'movie'
                      AND tb.genres::text[] @> ARRAY[:genre]::text[]
                      AND tr.num_votes >= :minVotes
                ),
                stats AS (SELECT AVG(average_rating) AS mean_rating FROM pool)
                SELECT p.tconst, p.primary_title, p.start_year, p.average_rating, p.num_votes,
                       (p.num_votes::numeric / (p.num_votes + :minVotes)) * p.average_rating
                       + (:minVotes::numeric / (p.num_votes + :minVotes)) * s.mean_rating AS weighted_rating
                FROM pool p CROSS JOIN stats s
                ORDER BY weighted_rating DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource()
                .addValue("genre", genre).addValue("minVotes", minVotes).addValue("limit", limit);
        return jdbc.query(sql, params, TitleRepository::mapTopRated);
    }

    public Optional<SharedTitle> findAnyCommonTitle(int personA, int personB) {
        String sql = """
                SELECT tb.tconst, tb.primary_title
                FROM title_principals p1
                JOIN title_principals p2 ON p1.tconst = p2.tconst
                JOIN title_basics tb ON tb.tconst = p1.tconst
                WHERE p1.nconst = :personA AND p2.nconst = :personB
                LIMIT 1
                """;
        var params = new MapSqlParameterSource().addValue("personA", personA).addValue("personB", personB);
        return jdbc.query(sql, params, (rs, rowNum) ->
                new SharedTitle(ImdbIds.formatTitleId(rs.getInt("tconst")), rs.getString("primary_title")))
                .stream().findFirst();
    }

    private List<CreditedPerson> findCrew(int tconst, String column) {
        // column is only ever "directors" or "writers" below - both fixed internal literals, never
        // user input - so string-formatting it into the SQL here isn't an injection risk. Bind
        // parameters can't stand in for column/identifier names, only values.
        String sql = """
                SELECT nb.nconst, nb.primary_name
                FROM title_crew tc
                CROSS JOIN LATERAL unnest(tc.%s) AS crew(nconst)
                JOIN name_basics nb ON nb.nconst = crew.nconst
                WHERE tc.tconst = :tconst
                """.formatted(column);
        return jdbc.query(sql, Map.of("tconst", tconst),
                (rs, rowNum) -> new CreditedPerson(
                        ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name")));
    }

    private static TitleSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new TitleSummary(
                ImdbIds.formatTitleId(rs.getInt("tconst")),
                rs.getString("primary_title"), rs.getString("original_title"), rs.getString("title_type"),
                (Integer) rs.getObject("start_year"), (Integer) rs.getObject("end_year"));
    }

    private static TitleCore mapCore(ResultSet rs, int rowNum) throws SQLException {
        List<String> genres = toStringList(rs.getArray("genres"));
        var avgRating = rs.getBigDecimal("average_rating");
        return new TitleCore(
                ImdbIds.formatTitleId(rs.getInt("tconst")),
                rs.getString("primary_title"), rs.getString("original_title"), rs.getString("title_type"),
                (Integer) rs.getObject("start_year"), (Integer) rs.getObject("end_year"),
                (Integer) rs.getObject("runtime_minutes"), genres,
                avgRating == null ? null : avgRating.doubleValue(),
                (Integer) rs.getObject("num_votes"));
    }

    private static CastMember mapCastMember(ResultSet rs, int rowNum) throws SQLException {
        return new CastMember(
                ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                rs.getString("category"), toStringList(rs.getArray("characters")), rs.getInt("ordering"));
    }

    private static GenreTopRatedItem mapTopRated(ResultSet rs, int rowNum) throws SQLException {
        return new GenreTopRatedItem(
                ImdbIds.formatTitleId(rs.getInt("tconst")), rs.getString("primary_title"),
                (Integer) rs.getObject("start_year"), rs.getBigDecimal("average_rating").doubleValue(),
                rs.getInt("num_votes"), rs.getBigDecimal("weighted_rating").doubleValue());
    }

    private static List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        return List.of((String[]) sqlArray.getArray());
    }

    public record TitleCore(String id, String primaryTitle, String originalTitle, String titleType,
                             Integer startYear, Integer endYear, Integer runtimeMinutes,
                             List<String> genres, Double averageRating, Integer numVotes) {}
}
```

`TitleCore` is `public` (nested inside `TitleRepository`) rather than living in `web.dto` - it's an
internal assembly shape `TitleDetailService` composes into the real `TitleDetail` DTO, not something we
ever hand back over the wire directly.

### `PersonRepository`

```java
package com.ludovictemgoua.imdb.repository;

import com.ludovictemgoua.imdb.ImdbIds;
import com.ludovictemgoua.imdb.web.dto.PersonCandidate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.stream.Collectors;

@Repository
public class PersonRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public PersonRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PersonCandidate> findByName(String name) {
        String sql = """
                SELECT nconst, primary_name, birth_year, known_for_titles
                FROM name_basics
                WHERE primary_name % :name
                ORDER BY similarity(primary_name, :name) DESC
                LIMIT 10
                """;
        return jdbc.query(sql, Map.of("name", name), PersonRepository::mapCandidate);
    }

    public Optional<String> findNameById(int nconst) {
        return jdbc.query("SELECT primary_name FROM name_basics WHERE nconst = :nconst",
                        Map.of("nconst", nconst), (rs, rowNum) -> rs.getString("primary_name"))
                .stream().findFirst();
    }

    public Map<Integer, String> findNamesByIds(Collection<Integer> nconsts) {
        if (nconsts.isEmpty()) return Map.of();
        String sql = "SELECT nconst, primary_name FROM name_basics WHERE nconst IN (:nconsts)";
        List<Map.Entry<Integer, String>> rows = jdbc.query(sql, Map.of("nconsts", nconsts),
                (rs, rowNum) -> Map.entry(rs.getInt("nconst"), rs.getString("primary_name")));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static PersonCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        Array knownForArr = rs.getArray("known_for_titles");
        List<String> knownFor = knownForArr == null ? List.of()
                : Arrays.stream((Integer[]) knownForArr.getArray())
                        .filter(Objects::nonNull).map(ImdbIds::formatTitleId).limit(3).toList();
        return new PersonCandidate(
                ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                (Integer) rs.getObject("birth_year"), knownFor);
    }
}
```

### `CoStarGraphRepository` - the centerpiece

This is the class the entire Six Degrees comparison in the LLD (§9, §5) exists to justify. Type it
carefully - it's the one place a subtle SQL mistake changes correctness, not just style.

```java
package com.ludovictemgoua.imdb.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class CoStarGraphRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final int sideCap;
    private final int fanOutCap;
    private final int absoluteMaxDegree;

    public CoStarGraphRepository(
            NamedParameterJdbcTemplate jdbc,
            @Value("${six-degrees.side-cap}") int sideCap,
            @Value("${six-degrees.fan-out-cap}") int fanOutCap,
            @Value("${six-degrees.absolute-max-degree}") int absoluteMaxDegree,
            @Value("${six-degrees.query-timeout-seconds}") int queryTimeoutSeconds) {
        this.jdbc = jdbc;
        this.sideCap = sideCap;
        this.fanOutCap = fanOutCap;
        this.absoluteMaxDegree = absoluteMaxDegree;
        // Only this query gets a tight timeout - it's the one query in the whole app whose cost
        // depends on graph shape (hub actors) rather than a bounded index lookup. There's no
        // `spring.jdbc.template.query-timeout` property to set this declaratively (checked against
        // the Boot 4.1 reference docs - it doesn't exist), so it's set directly on the underlying
        // JdbcTemplate instead.
        ((JdbcTemplate) jdbc.getJdbcOperations()).setQueryTimeout(queryTimeoutSeconds);
    }

    public Optional<RawMatch> findShortestPath(int personA, int personB) {
        String sql = """
                WITH RECURSIVE forward(person, depth, path) AS (
                    SELECT :personA, 0, ARRAY[:personA]
                  UNION ALL
                    SELECT nbr.person_b, f.depth + 1, f.path || nbr.person_b
                    FROM forward f
                    CROSS JOIN LATERAL (
                        SELECT e.person_b
                        FROM co_star_edges e
                        WHERE e.person_a = f.person
                        ORDER BY e.person_b
                        LIMIT :fanOutCap
                    ) nbr
                    WHERE f.depth < :sideCap
                      AND NOT nbr.person_b = ANY(f.path)
                ),
                backward(person, depth, path) AS (
                    SELECT :personB, 0, ARRAY[:personB]
                  UNION ALL
                    SELECT nbr.person_b, b.depth + 1, b.path || nbr.person_b
                    FROM backward b
                    CROSS JOIN LATERAL (
                        SELECT e.person_b
                        FROM co_star_edges e
                        WHERE e.person_a = b.person
                        ORDER BY e.person_b
                        LIMIT :fanOutCap
                    ) nbr
                    WHERE b.depth < :sideCap
                      AND NOT nbr.person_b = ANY(b.path)
                )
                SELECT f.depth + b.depth AS degree, f.path AS forward_path, b.path AS backward_path
                FROM forward f
                JOIN backward b ON b.person = f.person
                WHERE f.depth + b.depth <= :absoluteMaxDegree
                ORDER BY degree ASC
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("personA", personA).addValue("personB", personB)
                .addValue("sideCap", sideCap).addValue("fanOutCap", fanOutCap)
                .addValue("absoluteMaxDegree", absoluteMaxDegree);

        return jdbc.query(sql, params, CoStarGraphRepository::mapRawMatch).stream().findFirst();
    }

    private static RawMatch mapRawMatch(ResultSet rs, int rowNum) throws SQLException {
        return new RawMatch(rs.getInt("degree"),
                toIntList(rs.getArray("forward_path")), toIntList(rs.getArray("backward_path")));
    }

    private static List<Integer> toIntList(Array sqlArray) throws SQLException {
        return Arrays.asList((Integer[]) sqlArray.getArray());
    }

    public record RawMatch(int degree, List<Integer> forwardPath, List<Integer> backwardPath) {}
}
```

**Checkpoint**: `./mvnw compile` should succeed. This is a good point to write a throwaway `main` method
or a quick `@SpringBootTest` calling `findShortestPath` against two people you know are connected, just
to see actual rows come back, before building anything on top of it.

---

## Step 5: Error handling

`src/main/java/com/ludovictemgoua/imdb/error/`.

```java
package com.ludovictemgoua.imdb.error;

public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
```

```java
package com.ludovictemgoua.imdb.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadId(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }
}
```

That's deliberately shorter than you might expect: `@Min`/`@Max` violations on `@RequestParam` (the
`maxDegree` bound, pagination sizes) already get an automatic 400 `ProblemDetail` from Spring MVC itself
(via `HandlerMethodValidationException`, handled by default since Spring Framework 6.1/Boot 3.2) - no
handler needed here for that case at all. There's no handler yet for an invalid `genre` path segment
either (production's `genre` column is a real Postgres enum, and casting an invalid value to it throws a
`DataAccessException` subtype) - add one once you've actually triggered it and seen which exact subtype
Spring's JDBC exception translator produces; guessing it here would be worse than leaving it a gap.

---

## Step 6: Caching

`src/main/java/com/ludovictemgoua/imdb/config/CacheConfig.java`:

```java
package com.ludovictemgoua.imdb.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofHours(24))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .build();
    }
}
```

All four cache regions (`title-search`, `title-detail`, `top-rated`, `six-degrees`) share the same 24h TTL
per the LLD §6 table, so there's nothing to register per-name yet - `@Cacheable(cacheNames = "...")` on
the service methods (next step) creates each cache on first use from these defaults. If a region ever
needs its own TTL, `.withCacheConfiguration("name", customConfig)` is the extension point.

Using `GenericJackson2JsonRedisSerializer` (JSON in Redis, human-readable with `redis-cli GET`) instead of
the default JDK serialization is why every DTO being a plain record (Step 3) matters - records serialize
to/from JSON with zero extra configuration.

---

## Step 7: Services

`src/main/java/com/ludovictemgoua/imdb/service/`.

### The two sealed result types

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.web.dto.PersonCandidate;

import java.util.List;

public sealed interface PersonResolution {
    record Resolved(int nconst, String name) implements PersonResolution {}
    record Ambiguous(List<PersonCandidate> candidates) implements PersonResolution {}
    record NotFound() implements PersonResolution {}
}
```

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.web.dto.PersonCandidate;
import com.ludovictemgoua.imdb.web.dto.SixDegreesResult;

import java.util.List;

public sealed interface SixDegreesOutcome {
    record Found(SixDegreesResult result) implements SixDegreesOutcome {}
    record Ambiguous(String query, List<PersonCandidate> candidates) implements SixDegreesOutcome {}
    record PersonNotFound(String query) implements SixDegreesOutcome {}
}
```

These replace the `AmbiguousPersonException` the LLD's module layout (§2) originally sketched. Throwing
an exception for an *expected*, 200-status outcome (LLD §4.4 - disambiguation isn't an error) was always
a slight mismatch; a sealed interface plus an exhaustive `switch` (used in the controller, Step 8) makes
every caller handle every case at compile time instead, with no exception-as-control-flow.

### `PersonResolutionService`

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.ImdbIds;
import com.ludovictemgoua.imdb.repository.PersonRepository;
import com.ludovictemgoua.imdb.web.dto.PersonCandidate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PersonResolutionService {

    private final PersonRepository personRepository;

    public PersonResolutionService(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    public PersonResolution resolve(String query) {
        if (query.startsWith("nm")) {
            int nconst = ImdbIds.parsePersonId(query);
            return personRepository.findNameById(nconst)
                    .<PersonResolution>map(name -> new PersonResolution.Resolved(nconst, name))
                    .orElseGet(PersonResolution.NotFound::new);
        }
        List<PersonCandidate> candidates = personRepository.findByName(query);
        return switch (candidates.size()) {
            case 0 -> new PersonResolution.NotFound();
            case 1 -> {
                PersonCandidate only = candidates.get(0);
                yield new PersonResolution.Resolved(ImdbIds.parsePersonId(only.id()), only.name());
            }
            default -> new PersonResolution.Ambiguous(candidates);
        };
    }
}
```

### `DistanceCache` - why this is its own tiny class

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.repository.CoStarGraphRepository.RawMatch;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
class DistanceCache {

    private final CoStarGraphRepository graphRepository;

    DistanceCache(CoStarGraphRepository graphRepository) {
        this.graphRepository = graphRepository;
    }

    @Cacheable(cacheNames = "six-degrees",
            key = "T(java.lang.Math).min(#personA, #personB) + '-' + T(java.lang.Math).max(#personA, #personB)")
    RawMatch trueShortestPath(int personA, int personB) {
        return graphRepository.findShortestPath(personA, personB).orElse(null);
    }
}
```

This isn't a style choice - it's a real Spring pitfall being avoided. `@Cacheable` works by wrapping the
bean in a proxy; a call from *within the same bean* (`this.trueShortestPath(...)`) bypasses that proxy
entirely and silently never hits the cache. Putting the cached method on its own small collaborator,
called from `SixDegreesService` through the injected reference, sidesteps the problem rather than
requiring everyone who touches `SixDegreesService` later to remember not to call this method internally.
The cache key deliberately ignores argument order (`min`/`max`) and any `maxDegree` - per LLD §6, the
cached value is the *true* shortest distance up to the absolute 7-degree cap, independent of what bound
any particular caller asked for.

Returning `null` (not `Optional`) for "no path found" matches this codebase's existing convention
(`votee`'s LLD flags the same choice for `Candidate.party`) - `Optional` is for return types callers
branch on, not for storing in a field or, here, a cache entry.

### The straightforward services

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.repository.TitleRepository;
import com.ludovictemgoua.imdb.web.dto.PagedResult;
import com.ludovictemgoua.imdb.web.dto.TitleSummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class TitleSearchService {

    private final TitleRepository titleRepository;

    public TitleSearchService(TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    @Cacheable(cacheNames = "title-search", key = "#query + ':' + #page + ':' + #size")
    public PagedResult<TitleSummary> search(String query, int page, int size) {
        return titleRepository.search(query, page, size);
    }
}
```

`TitleDetailService` and `TopRatedService` follow the exact same one-method, `@Cacheable`-wrapping shape:

- `TitleDetailService.getDetail(String titleId)`: parse the id with `ImdbIds`, call
  `titleRepository.findCore(...)` (throwing `NotFoundException` on empty), then
  `findDirectors`/`findWriters`/`findTopCast`/`countCast`, and assemble a `TitleDetail` from the pieces.
  Cache key is just `#titleId`.
- `TopRatedService.findTopRated(String genre, int limit, Integer minVotes)`: if `minVotes` is `null`, fall
  back to the injected `${top-rated.default-min-votes}` value (this is the LLD §11/PDD §11 open item -
  1000 is a placeholder until you've looked at the real vote-count distribution), then delegate to
  `titleRepository.findTopRated(...)`. Cache key is `#genre + ':' + #limit + ':' + #minVotes`.

Writing these two yourself is the point - `TitleSearchService` above is the complete pattern; there's
nothing left to discover from having it typed out a second time.

### `SixDegreesService` - the orchestrator

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.ImdbIds;
import com.ludovictemgoua.imdb.graph.PathStitcher;
import com.ludovictemgoua.imdb.repository.CoStarGraphRepository.RawMatch;
import com.ludovictemgoua.imdb.repository.PersonRepository;
import com.ludovictemgoua.imdb.repository.TitleRepository;
import com.ludovictemgoua.imdb.web.dto.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SixDegreesService {

    private final PersonResolutionService personResolution;
    private final DistanceCache distanceCache;
    private final PersonRepository personRepository;
    private final TitleRepository titleRepository;

    SixDegreesService(PersonResolutionService personResolution, DistanceCache distanceCache,
                       PersonRepository personRepository, TitleRepository titleRepository) {
        this.personResolution = personResolution;
        this.distanceCache = distanceCache;
        this.personRepository = personRepository;
        this.titleRepository = titleRepository;
    }

    public SixDegreesOutcome compute(String queryA, String queryB, int maxDegree) {
        PersonResolution resolvedA = personResolution.resolve(queryA);
        if (resolvedA instanceof PersonResolution.Ambiguous a) {
            return new SixDegreesOutcome.Ambiguous(queryA, a.candidates());
        }
        if (resolvedA instanceof PersonResolution.NotFound) {
            return new SixDegreesOutcome.PersonNotFound(queryA);
        }
        PersonResolution resolvedB = personResolution.resolve(queryB);
        if (resolvedB instanceof PersonResolution.Ambiguous b) {
            return new SixDegreesOutcome.Ambiguous(queryB, b.candidates());
        }
        if (resolvedB instanceof PersonResolution.NotFound) {
            return new SixDegreesOutcome.PersonNotFound(queryB);
        }

        var a = (PersonResolution.Resolved) resolvedA;
        var b = (PersonResolution.Resolved) resolvedB;
        PersonRef personA = new PersonRef(ImdbIds.formatPersonId(a.nconst()), a.name());
        PersonRef personB = new PersonRef(ImdbIds.formatPersonId(b.nconst()), b.name());

        if (a.nconst() == b.nconst()) {
            PathStep onlyStep = new PathStep(personA.id(), personA.name(), null);
            return new SixDegreesOutcome.Found(
                    new SixDegreesResult(personA, personB, 0, true, List.of(onlyStep)));
        }

        RawMatch match = distanceCache.trueShortestPath(a.nconst(), b.nconst());
        if (match == null) {
            return new SixDegreesOutcome.Found(
                    new SixDegreesResult(personA, personB, null, false, List.of()));
        }

        boolean withinMax = match.degree() <= maxDegree;
        List<PathStep> path = withinMax ? buildPath(PathStitcher.stitch(match)) : List.of();
        return new SixDegreesOutcome.Found(
                new SixDegreesResult(personA, personB, match.degree(), withinMax, path));
    }

    private List<PathStep> buildPath(List<Integer> nconsts) {
        Map<Integer, String> names = personRepository.findNamesByIds(nconsts);
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < nconsts.size(); i++) {
            int nconst = nconsts.get(i);
            SharedTitle sharedTitle = i == 0 ? null
                    : titleRepository.findAnyCommonTitle(nconsts.get(i - 1), nconst).orElse(null);
            steps.add(new PathStep(ImdbIds.formatPersonId(nconst), names.get(nconst), sharedTitle));
        }
        return steps;
    }
}
```

Note the early returns for `Ambiguous`/`NotFound` on `resolvedA` happen *before* resolving `queryB` at
all - no point spending a second trigram query if the first side already failed.

### `graph/PathStitcher`

`src/main/java/com/ludovictemgoua/imdb/graph/PathStitcher.java`:

```java
package com.ludovictemgoua.imdb.graph;

import com.ludovictemgoua.imdb.repository.CoStarGraphRepository.RawMatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PathStitcher {

    private PathStitcher() {}

    public static List<Integer> stitch(RawMatch match) {
        List<Integer> backward = new ArrayList<>(match.backwardPath());
        Collections.reverse(backward);
        // forward's last element and reversed-backward's first element are the same node (where the
        // two searches met) - drop the duplicate before concatenating.
        List<Integer> stitched = new ArrayList<>(match.forwardPath());
        stitched.addAll(backward.subList(1, backward.size()));
        return stitched;
    }
}
```

**Checkpoint**: `./mvnw compile` should succeed with the full service layer in place.

---

## Step 8: Web layer

`src/main/java/com/ludovictemgoua/imdb/web/`.

```java
package com.ludovictemgoua.imdb.web;

import com.ludovictemgoua.imdb.service.TitleDetailService;
import com.ludovictemgoua.imdb.service.TitleSearchService;
import com.ludovictemgoua.imdb.web.dto.PagedResult;
import com.ludovictemgoua.imdb.web.dto.TitleDetail;
import com.ludovictemgoua.imdb.web.dto.TitleSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/titles")
@Validated
public class TitleController {

    private final TitleSearchService searchService;
    private final TitleDetailService detailService;

    public TitleController(TitleSearchService searchService, TitleDetailService detailService) {
        this.searchService = searchService;
        this.detailService = detailService;
    }

    @GetMapping("/search")
    public PagedResult<TitleSummary> search(
            @RequestParam @NotBlank String title,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return searchService.search(title, page, size);
    }

    @GetMapping("/{titleId}")
    public TitleDetail get(@PathVariable String titleId) {
        return detailService.getDetail(titleId);
    }
}
```

`GenreController` follows the identical shape - `@RestController` + `@RequestMapping("/api/v1/genres")` +
`@Validated`, one `@GetMapping("/{genre}/top-rated")` method taking `genre` as a `@PathVariable` and
`limit`/`minVotes` as validated `@RequestParam`s, delegating to `TopRatedService`.

`PersonController` is the one worth typing out in full, since it's where the sealed `SixDegreesOutcome`
from Step 7 actually gets consumed:

```java
package com.ludovictemgoua.imdb.web;

import com.ludovictemgoua.imdb.service.SixDegreesOutcome;
import com.ludovictemgoua.imdb.service.SixDegreesService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/people")
@Validated
public class PersonController {

    private final SixDegreesService sixDegreesService;

    public PersonController(SixDegreesService sixDegreesService) {
        this.sixDegreesService = sixDegreesService;
    }

    @GetMapping("/six-degrees")
    public ResponseEntity<?> sixDegrees(
            @RequestParam String personA,
            @RequestParam String personB,
            @RequestParam(defaultValue = "7") @Min(1) @Max(7) int maxDegree) {

        SixDegreesOutcome outcome = sixDegreesService.compute(personA, personB, maxDegree);
        return switch (outcome) {
            case SixDegreesOutcome.Found found -> ResponseEntity.ok(found.result());
            case SixDegreesOutcome.Ambiguous amb -> ResponseEntity.ok(Map.of(
                    "requiresDisambiguation", true, "query", amb.query(), "candidates", amb.candidates()));
            case SixDegreesOutcome.PersonNotFound nf -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.NOT_FOUND, "No person matching: " + nf.query()));
        };
    }
}
```

The `switch` over `outcome` needs no `default` branch - `SixDegreesOutcome` is `sealed` with exactly
three implementations, so the compiler already knows the switch is exhaustive. Delete one of the `case`
branches as an experiment and watch it fail to compile - that guarantee is the entire reason to reach for
a sealed interface over a plain exception or a nullable field here.

**Checkpoint**: `./mvnw spring-boot:test-run` should let you `curl localhost:8080/api/v1/people/six-degrees?personA=nm...&personB=nm...` end to end.

---

## Step 9: Tests

One example per layer - `votee`'s existing test style (JUnit 5, Mockito, AssertJ) carries over directly.

### Unit test (mocked collaborators, no Spring context)

```java
package com.ludovictemgoua.imdb.service;

import com.ludovictemgoua.imdb.repository.PersonRepository;
import com.ludovictemgoua.imdb.repository.TitleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class SixDegreesServiceTest {

    @Mock PersonResolutionService personResolution;
    @Mock DistanceCache distanceCache;
    @Mock PersonRepository personRepository;
    @Mock TitleRepository titleRepository;

    @Test
    void sameResolvedPersonIsDegreeZero() {
        var service = new SixDegreesService(personResolution, distanceCache, personRepository, titleRepository);
        given(personResolution.resolve("nm0000102"))
                .willReturn(new PersonResolution.Resolved(102, "Kevin Bacon"));
        given(personResolution.resolve("Kevin Bacon"))
                .willReturn(new PersonResolution.Resolved(102, "Kevin Bacon"));

        var outcome = service.compute("nm0000102", "Kevin Bacon", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Found.class,
                found -> assertThat(found.result().degree()).isZero());
    }
}
```

### Controller test (`@WebMvcTest`, real HTTP-shaped request, mocked service)

```java
package com.ludovictemgoua.imdb.web;

import com.ludovictemgoua.imdb.service.SixDegreesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PersonController.class)
class PersonControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean SixDegreesService sixDegreesService;

    @Test
    void rejectsMaxDegreeAboveSeven() throws Exception {
        mockMvc.perform(get("/api/v1/people/six-degrees")
                        .param("personA", "nm0000102")
                        .param("personB", "nm0000158")
                        .param("maxDegree", "9"))
                .andExpect(status().isBadRequest());
    }
}
```

`@MockitoBean` (Spring Framework's own annotation, `org.springframework.test.context.bean.override.mockito`)
is the current replacement for the older `@MockBean` - checked against the Boot 4.1 testing reference
docs directly rather than assumed, since `@MockBean` doesn't appear there anymore.

### Integration test (Testcontainers, real Flyway migrations, fixture data)

```java
package com.ludovictemgoua.imdb.integration;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import com.ludovictemgoua.imdb.repository.CoStarGraphRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Sql("/fixtures/fixture-data.sql")
class CoStarGraphRepositoryIntegrationTest {

    @Autowired CoStarGraphRepository repository;

    @Test
    void findsShortestPathAcrossMultipleHopsOnBothSides() {
        // pick two nconst values from your fixture-data.sql whose true shortest path requires
        // more than one hop on each side of the bidirectional search
        var result = repository.findShortestPath(1001, 1006);

        assertThat(result).isPresent();
        assertThat(result.get().degree()).isEqualTo(4);
    }
}
```

`@Sql` runs *after* the Spring context (and therefore Flyway's `V0`/`V1`/`V2`) is already up, so by the
time it executes, `co_star_edges` and the rest of the schema already exist - `fixture-data.sql` only ever
needs `INSERT` statements. Write `src/test/resources/fixtures/fixture-data.sql` yourself: a small,
hand-picked set of `name_basics`/`title_basics`/`title_principals` rows forming a co-star chain you know
the answer to, per the LLD §10 test plan - designing that fixture (rather than having it handed to you)
is arguably the best way to prove to yourself the bidirectional CTE actually does what §5 claims.

---

## What's left

This covers every class in the LLD's module layout (§2) except the ones that turned out unnecessary along
the way (`JdbcConfig` - autoconfigured already; `AmbiguousPersonException` - replaced by the sealed
`SixDegreesOutcome`; `OpenApiConfig` - springdoc isn't available for Boot 4.1 yet, LLD §11). Once this
compiles and the checkpoints above all pass, the remaining LLD §11 open items apply: the k6 scripts, the
Grafana dashboard JSON, and tuning `fan-out-cap`/`side-cap`/`default-min-votes` against real data and real
load-test results instead of the placeholders used here.
