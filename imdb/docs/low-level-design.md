# IMDb Copycat API - Low-Level Design Document

| | |
|---|---|
| Author | Ludovic Temgoua Abanda |
| Status | Draft |
| Date | 2026-07-05 |
| Related docs | `imdb/docs/product-design.md` (PDD, approved), `imdb/docs/REQUIREMENTS.md` |
| Data source | [`abanda/imdb-postgresql`](https://github.com/icemc/imdb-postgresql) |

## 1. Purpose and Scope

The PDD defines what is being built and why. This document defines how: schema additions on top of the
seeded database, endpoint contracts, the bidirectional-CTE SQL for Six Degrees, the caching strategy,
docker-compose topology, observability wiring, and the test plan. This is a design reference for manual
implementation, not generated production code - the Maven project itself (`pom.xml`, `src/`) does not yet
exist in this repository; scaffolding it via [start.spring.io](https://start.spring.io/) is the first
implementation step once this document is approved (see §11).

## 2. Maven Module Layout (target state)

```
imdb/
  pom.xml
  Dockerfile
  docker-compose.yaml
  docs/
    REQUIREMENTS.md
    product-design.md
    low-level-design.md
  observability/
    prometheus/prometheus.yml
    tempo/tempo.yaml
    alloy/config.alloy
    grafana/provisioning/
      datasources/datasources.yml
      dashboards/dashboards.yml
      dashboards/json/            # dashboard JSON models, authored during implementation
  k6/
    search.js
    title-detail.js
    top-rated.js
    six-degrees.js
    data/sampled-people.csv       # actor pool used by six-degrees.js to defeat caching
  src/
    main/
      java/com/ludovictemgoua/imdb/
        ImdbApplication.java
        config/
          CacheConfig.java
          JdbcConfig.java
          OpenApiConfig.java
        web/
          TitleController.java
          GenreController.java
          PersonController.java
          dto/
            TitleSummary.java
            TitleDetail.java
            RatingView.java
            CastMember.java
            CreditedPerson.java
            GenreTopRatedItem.java
            SixDegreesResult.java
            PersonCandidate.java
        service/
          TitleSearchService.java
          TitleDetailService.java
          TopRatedService.java
          PersonResolutionService.java
          SixDegreesService.java
        repository/
          TitleRepository.java
          PersonRepository.java
          CoStarGraphRepository.java
        graph/
          BidirectionalPathResult.java
          PathStitcher.java
        error/
          ApiExceptionHandler.java
          NotFoundException.java
          AmbiguousPersonException.java
      resources/
        application.yml
        db/migration/
          V1__extensions_and_search_indexes.sql
          V2__co_star_edges_materialized_view.sql
    test/
      java/com/ludovictemgoua/imdb/
        web/ (controller tests, MockMvc)
        service/ (unit tests, mocked repositories)
        integration/ (Testcontainers + fixture dataset)
      resources/
        fixtures/
          fixture-schema.sql
          fixture-data.sql
```

`application.yml`, not `.properties`: the config surface is more nested than a typical CRUD app -
`management.metrics.*`, `management.tracing.*`, and `management.otlp.*` (§7) sit several levels deep
alongside a custom `six-degrees.*` group (§5/§6), and YAML's multi-document `---` profiles let the
Testcontainers-backed integration tests (§10) override just the datasource block against the fixture
container instead of `docker-compose.yaml`'s Postgres, without duplicating everything else.

## 3. Data Layer

### 3.1 Why plain JDBC, not JPA/Hibernate

Every query this API needs is a hand-tuned native query: trigram similarity search, GIN array containment
for genres, a Bayesian weighted-rating computation, and a recursive CTE for graph traversal. None of these
benefit from Hibernate's object-relational mapping or dirty-checking - there is no object graph being
mutated, only read projections. Introducing JPA here would mean fighting it (native `@Query` everywhere)
for zero benefit. The design instead uses Spring's `NamedParameterJdbcTemplate` with explicit `RowMapper`s
throughout, and Flyway for the two migrations below - one data-access style, not two.

### 3.2 Migration V1: extensions and search indexes

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_title_basics_primary_title_trgm
    ON title_basics USING gin (primary_title gin_trgm_ops);

CREATE INDEX idx_title_basics_original_title_trgm
    ON title_basics USING gin (original_title gin_trgm_ops);

CREATE INDEX idx_title_basics_genres
    ON title_basics USING gin (genres);

CREATE INDEX idx_title_ratings_rank
    ON title_ratings (average_rating DESC, num_votes DESC);

CREATE INDEX idx_title_principals_nconst_acting
    ON title_principals (nconst)
    WHERE category IN ('actor', 'actress', 'self');

CREATE INDEX idx_name_basics_primary_name_trgm
    ON name_basics USING gin (primary_name gin_trgm_ops);
```

### 3.3 Migration V2: the co-star edge view

The recursive CTE in §5 needs to expand "who has this person co-starred with" on every hop. Doing that
directly against `title_principals` means a self-join over the full table (tens of millions of rows,
filtered twice) on every single hop of every query. Instead, a materialized view precomputes the edge list
once:

```sql
CREATE MATERIALIZED VIEW co_star_edges AS
SELECT DISTINCT p1.nconst AS person_a, p2.nconst AS person_b
FROM title_principals p1
JOIN title_principals p2
    ON p1.tconst = p2.tconst
   AND p1.nconst <> p2.nconst
WHERE p1.category IN ('actor', 'actress', 'self')
  AND p2.category IN ('actor', 'actress', 'self');

CREATE UNIQUE INDEX idx_co_star_edges_pk ON co_star_edges (person_a, person_b);
```

Note this is deliberately stored **directionally symmetric** (both `(a, b)` and `(b, a)` rows exist,
since the self-join naturally produces both orderings) rather than deduplicated with `LEAST`/`GREATEST`.
That trades roughly 2x storage for a simpler, single-direction lookup (`WHERE person_a = ?`) in the hot
path, instead of a `UNION ALL` on every hop of the recursive CTE.

**Refresh strategy**: `REFRESH MATERIALIZED VIEW CONCURRENTLY co_star_edges` once after the one-time
dataset import completes (there is no write path afterward - see PDD §11's open question on this). A
`REFRESH ... CONCURRENTLY` requires the unique index above, which is already in place.

### 3.4 ID translation at the boundary

`tconst`/`nconst` are stored as plain `INTEGER`. The API never exposes these directly:

```java
public final class ImdbIds {
    public static int parseTitleId(String tt) { // "tt0111161" -> 111161
        return Integer.parseInt(requirePrefix(tt, "tt"));
    }
    public static int parsePersonId(String nm) { // "nm0000102" -> 102
        return Integer.parseInt(requirePrefix(nm, "nm"));
    }
    public static String formatTitleId(int tconst) { return "tt" + pad7(tconst); }
    public static String formatPersonId(int nconst) { return "nm" + pad7(nconst); }
}
```

A malformed ID (wrong prefix, non-numeric suffix) is a `400` at the controller boundary via a
`@RequestParam`/`@PathVariable` converter, before it ever reaches a service or query.

## 4. Endpoint Specifications

### 4.1 `GET /api/v1/titles/search?title={q}&page=&size=`

Trigram similarity search, ordered by match quality:

```sql
SELECT tconst, primary_title, original_title, title_type, start_year, end_year,
       similarity(primary_title, :query) AS score
FROM title_basics
WHERE primary_title % :query OR original_title % :query
ORDER BY score DESC
LIMIT :size OFFSET :offset;
```

(`%` is `pg_trgm`'s similarity operator, index-backed by the GIN indexes in §3.2.) Response: a
`Page<TitleSummary>` (`id`, `primaryTitle`, `originalTitle`, `titleType`, `startYear`, `endYear`).

### 4.2 `GET /api/v1/titles/{titleId}`

Three queries (title metadata + rating; directors/writers via `title_crew` joined to `name_basics`;
top-billed cast via `title_principals` joined to `name_basics`, ordered by `ordering`, capped at 20 with a
total count), composed into one `TitleDetail`:

```json
{
  "id": "tt0111161",
  "primaryTitle": "The Shawshank Redemption",
  "originalTitle": "The Shawshank Redemption",
  "titleType": "movie",
  "startYear": 1994,
  "runtimeMinutes": 142,
  "genres": ["Drama"],
  "rating": { "average": 9.3, "numVotes": 2900000 },
  "directors": [{ "id": "nm0001104", "name": "Frank Darabont" }],
  "writers": [{ "id": "nm0001104", "name": "Frank Darabont" }],
  "cast": [
    { "id": "nm0000209", "name": "Tim Robbins", "category": "actor", "characters": ["Andy Dufresne"], "ordering": 1 }
  ],
  "castTotalCount": 20
}
```

`404` (via `NotFoundException` -> `ProblemDetail`) if `tconst` doesn't exist in `title_basics`.

### 4.3 `GET /api/v1/genres/{genre}/top-rated?limit=&minVotes=`

Restricted to `title_type = 'movie'`, ranked by weighted rating rather than raw average (PDD §9):

```sql
WITH pool AS (
    SELECT tb.tconst, tb.primary_title, tb.start_year, tr.average_rating, tr.num_votes
    FROM title_basics tb
    JOIN title_ratings tr ON tr.tconst = tb.tconst
    WHERE tb.title_type = 'movie'
      AND tb.genres @> ARRAY[:genre]::genre[]
      AND tr.num_votes >= :minVotes
),
stats AS (
    SELECT AVG(average_rating) AS mean_rating FROM pool
)
SELECT p.tconst, p.primary_title, p.start_year, p.average_rating, p.num_votes,
       (p.num_votes::numeric / (p.num_votes + :minVotes)) * p.average_rating
       + (:minVotes::numeric / (p.num_votes + :minVotes)) * s.mean_rating AS weighted_rating
FROM pool p CROSS JOIN stats s
ORDER BY weighted_rating DESC
LIMIT :limit;
```

`minVotes` (the Bayesian `m`) defaults to a value chosen from the actual vote-count distribution once the
dataset is loaded (PDD §11 open question) rather than an arbitrary round number; `mean_rating` (the
Bayesian `C`) is computed live over the qualifying pool so it self-adjusts per genre rather than using a
single global constant.

### 4.4 `GET /api/v1/people/six-degrees?personA=&personB=&maxDegree=`

Accepts either `personId` (`nm...`) or `name` for each side. See §5 for the full algorithm; response
shape:

```json
{
  "personA": { "id": "nm0000102", "name": "Kevin Bacon" },
  "personB": { "id": "nm0000158", "name": "Tom Hanks" },
  "degree": 2,
  "withinRequestedMax": true,
  "path": [
    { "id": "nm0000102", "name": "Kevin Bacon" },
    { "id": "nm0000129", "name": "Tom Cruise", "sharedTitle": { "id": "tt0100405", "primaryTitle": "A Few Good Men" } },
    { "id": "nm0000158", "name": "Tom Hanks", "sharedTitle": { "id": "tt0181689", "primaryTitle": "The Terminal", "note": "illustrative" } }
  ]
}
```

If a `name` matches more than one person, the response is a disambiguation payload instead
(`{"requiresDisambiguation": true, "query": "...", "candidates": [...]}`, HTTP `200`) rather than an
error - it's an expected, common case (many people share a name), not a client mistake. `maxDegree` is
validated to `1..=7` (`400` outside that range).

## 5. Six Degrees: Bidirectional Recursive CTE

### 5.1 Why bidirectional, not the naive one-sided walk

A one-sided recursive walk from person A, expanding until person B is found, pays the graph's full
branching factor for every hop - and this graph has extreme hub nodes (some credited actors have
thousands of co-stars). Meeting in the middle from both ends roughly squares down the search space
(`b^(d/2)` instead of `b^d`), and combined with the product's own 7-degree cap, each side only ever needs
to expand `⌈7/2⌉ = 4` hops. See PDD §9 for the full comparison against precomputed BFS, in-memory BFS, and
Pruned Landmark Labeling.

### 5.2 The query

```sql
WITH RECURSIVE forward(person, depth, path) AS (
    SELECT :personA::int, 0, ARRAY[:personA::int]
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
    SELECT :personB::int, 0, ARRAY[:personB::int]
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
LIMIT 1;
```

- `:sideCap` = 4 (fixed at `⌈7/2⌉`) and `:absoluteMaxDegree` = 7, both independent of the caller's
  requested `maxDegree` - see caching rationale in §6. The explicit `WHERE` matters: with `sideCap=4` on
  each side, the two CTEs can meet at combined depth 8, one more than the product's absolute cap. Without
  this filter that would surface as a spurious "degree 8" answer; with it, no matching row means the
  service layer cleanly reports "no path found within 7 degrees" - a plain empty result, not a value that
  needs post-hoc clipping.
- `:fanOutCap` (e.g. 200) bounds how many neighbors a single hub node contributes per hop. This is an
  explicit, documented approximation: if a node's true shortest-path edge falls outside its top-200
  neighbors by `person_b` ordering, that path is missed. This is the practical trade-off that makes the
  hub-blowup problem tractable in plain SQL; it is a config knob (`six-degrees.fan-out-cap`), not a hidden
  magic number, so it can be tuned or disabled (`LIMIT NULL`-equivalent) if load testing shows it's overly
  aggressive.
- `statement_timeout` is set per-query (e.g. `SET LOCAL statement_timeout = '2000ms'`) as a last-resort
  circuit breaker on top of the two caps above.
- The application resolves the winning `forward_path`/`backward_path` into a single displayed path by
  reversing `backward_path` and concatenating, dropping the duplicated meeting node - this stitching
  happens in `PathStitcher`, not in SQL.
- `personA == personB` is special-cased in `SixDegreesService` to return degree `0` without running the
  query at all.

### 5.3 Person resolution

`PersonResolutionService` resolves a `name` query against `name_basics` via the trigram index from §3.2.
Exactly one strong match (similarity above a threshold and no close runner-up) proceeds directly; multiple
plausible matches return the disambiguation payload from §4.4, including `birthYear` and a couple of
`knownForTitles` entries per candidate so a human can tell "Michael J. Fox" from another same-named person
at a glance.

## 6. Caching Strategy

Redis, cache-aside, via Spring's `@Cacheable`/`CacheManager`, one cache region per endpoint:

| Cache | Key | TTL | Notes |
|---|---|---|---|
| `title-search` | `title, page, size` | 24h | Small enough result sets that full-parameter keying is fine |
| `title-detail` | `titleId` | 24h | Highest hit-rate cache; title detail is the most repeatable lookup |
| `top-rated` | `genre, limit, minVotes` | 24h | |
| `six-degrees` | `min(personA,personB):max(personA,personB)` (**not** including `maxDegree`) | 24h | Stores the true shortest distance up to the absolute 7-degree cap; a request with a smaller `maxDegree` is served from the same cache entry and simply reports "beyond requested max" without recomputation (PDD §9) |

All TTLs are long because the underlying dataset only changes when the Docker image is reloaded - there
is no write path invalidating these entries mid-flight. A full `FLUSHDB` on redeploy is the accepted
invalidation strategy, documented rather than automated, since there's no signal in the running system
that would tell it the data changed underneath it.

**Load-test interaction** (carried over from the PDD discussion): the `six-degrees` k6 script must draw a
different, pre-sampled person pair per iteration specifically so it exercises the bidirectional CTE
instead of just measuring a warm Redis round-trip after the first request - see §8.

## 7. Observability Wiring

- **Metrics**: `micrometer-registry-prometheus`, default HTTP/JVM metrics plus custom ones: a `Timer` per
  endpoint's DB-query phase (so the six-degrees CTE's latency is visible as its own series, separate from
  total request latency), and `Counter`s for cache hit/miss per cache region.
- **Tracing**: `micrometer-tracing-bridge-otel` + OTLP exporter to Tempo
  (`management.otlp.tracing.endpoint=http://tempo:4318/v1/traces`, matching `docker-compose.yaml`).
  `management.tracing.sampling.probability=1.0` for this exercise (full sampling; would be tuned down in
  a real production deployment under real traffic volume).
- **Logging**: structured JSON via Spring Boot's built-in structured logging support, trace/span IDs
  included automatically via Micrometer Tracing's MDC integration, shipped to Loki via Grafana Alloy
  (already wired in `docker-compose.yaml` / `observability/alloy/config.alloy`) - no application-side
  logging-shipper dependency needed.
- **Correlation**: `observability/grafana/provisioning/datasources/datasources.yml` already wires
  Loki-derived-fields -> Tempo and Tempo -> Loki/Prometheus, so a trace opened in Grafana click-throughs to
  its log lines and vice versa.
- **Dashboards**: provisioned via `observability/grafana/provisioning/dashboards/dashboards.yml`, pointing
  at a `dashboards/json/` folder. Actual dashboard JSON models (HTTP overview, six-degrees CTE latency
  breakdown, cache hit ratio, k6 load-test results) are authored during implementation, not shipped as
  part of this design (see §11).

## 8. k6 Load Testing Plan

One script per endpoint under `imdb/k6/`, run one at a time (never concurrently) so each run's metrics
are attributable to a single endpoint:

| Script | Pattern | Notes |
|---|---|---|
| `search.js` | Ramping VUs (0 -> 50 -> 100 -> 0), random query terms from a word list | |
| `title-detail.js` | Ramping VUs, random `tconst` sampled from a pre-fetched pool | |
| `top-rated.js` | Ramping VUs, cycles through all genre enum values | |
| `six-degrees.js` | Ramping VUs, **each iteration picks a distinct person pair** from `data/sampled-people.csv` (mix of ordinary and high-degree "hub" actors) | Deliberately defeats the Redis cache (§6) so the bidirectional CTE's real behavior under load is what gets measured, not cache round-trip time |

Each script: `p(95) < <threshold>` and `error rate < 1%` thresholds, tuned per endpoint (the six-degrees
threshold is expected to be materially higher than the others - that gap *is* the finding). All four
output via `--out experimental-prometheus-rw` (`K6_PROMETHEUS_RW_SERVER_URL` already set in
`docker-compose.yaml`), so a load-test run is visible in Grafana alongside the application's own
traces/metrics for that time window. `k6` runs behind the `load-test` compose profile:

```
docker-compose --profile load-test run k6 run /scripts/six-degrees.js
```

## 9. Error Handling & API Conventions

- All errors are RFC 7807 `ProblemDetail` (Spring Boot 3 built-in): `404` for unknown IDs, `400` for
  malformed IDs / out-of-range `maxDegree` / missing required query params.
- Six-degrees disambiguation is `200`, not an error status - see §4.4.
- Pagination via Spring Data `Pageable` (`page`, `size`, default `sort`) on search and top-rated.
- `springdoc-openapi` for a self-serve Swagger UI.

## 10. Test Plan

The seeded `abanda/imdb-postgresql` image is ~20GB and takes 20-30 minutes to import - unusable as a CI
dependency. Testing splits accordingly:

- **Unit tests**: service layer against mocked repositories (JUnit 5 + Mockito + AssertJ, matching
  `votee`'s existing testing style in this monorepo).
- **Integration tests**: Testcontainers running a plain `postgres:17` image, migrated with the real
  `V1`/`V2` migrations from §3 plus a small hand-authored fixture dataset
  (`fixture-schema.sql`/`fixture-data.sql`) covering: a known multi-hop co-star chain (to exercise the
  bidirectional CTE end-to-end, including a deliberate case where the true path requires more than one
  hop on each side), a tied weighted-rating case, and an ambiguous shared name. Fast and deterministic.
- **Controller tests**: `MockMvc`, request validation and `ProblemDetail` error-shape contracts.
- **CI**: GitHub Actions, matching `votee`'s existing workflow pattern - unit + integration tests against
  the lightweight fixture only.
- **Load tests**: k6 (§8), run manually against the fully-seeded stack, not part of the CI gate.

## 11. Open Items for Implementation

- Scaffold the Maven project via [start.spring.io](https://start.spring.io/): Java 21, Spring Boot 4.1.0,
  group `com.ludovictemgoua`, artifact `imdb`. All dependencies below are selectable directly on
  Initializr - no post-generation pom edits needed:

  | Dependency (Initializr label) | Include? | Why |
  |---|---|---|
  | Spring Web | Yes | REST controllers |
  | JDBC API | Yes | `NamedParameterJdbcTemplate` access - **not** Spring Data JDBC, see §3.1 (avoids reintroducing a repository/entity abstraction) |
  | PostgreSQL Driver | Yes | Connects to `abanda/imdb-postgresql` |
  | Flyway Migration | Yes | Runs the `V1`/`V2` migrations from §3 |
  | Spring Data Redis | Yes | Cache-aside layer, §6 |
  | Validation | Yes | `maxDegree` range / malformed-ID checks at the controller boundary |
  | Spring Boot Actuator | Yes | Health, `/actuator/prometheus` |
  | Prometheus | Yes | Micrometer -> Prometheus format, scraped by the `prometheus` compose service |
  | Distributed Tracing | Yes | Span/trace IDs in logs - log<->trace correlation, §7 |
  | OpenTelemetry | Yes | Publishes traces via OTLP to Tempo, pairs with Distributed Tracing |
  | springdoc-openapi | Yes | Swagger UI |
  | Testcontainers | Yes | Backs the integration-test plan, §10 |
  | datasource-micrometer | No | Its Initializr `versionRange` is `[3.5.0.RELEASE, 4.1.0.M1)` - not yet ported to the Boot 4.1 line. The gap is covered anyway: Spring Boot auto-binds HikariCP connection-pool metrics via Actuator/Micrometer with no extra dependency, and the one query worth watching closely (the six-degrees CTE) already gets a hand-rolled `Timer` per §7. |
  | Lombok | No | This monorepo uses Java records for value types (see `votee`), not Lombok-generated boilerplate |
  | Docker Compose (dev-tool) | No | Conflicts with our hand-authored `docker-compose.yaml`, which already includes the app itself as a service |
  | Zipkin | No | Redundant trace backend - traces already go via OpenTelemetry/OTLP to Tempo |
  | otlp-metrics | No | Redundant metrics path - metrics already go via Prometheus pull-scrape |
- Write the multi-stage `Dockerfile` (Maven build stage -> JRE runtime stage) referenced by
  `docker-compose.yaml`'s `imdb-service.build`.
- Author the actual Grafana dashboard JSON models under `observability/grafana/provisioning/dashboards/json/`.
- Author the k6 scripts and the sampled-actor CSV under `imdb/k6/`.
- Pick the concrete `minVotes` default for the weighted-rating formula from the real vote-count
  distribution once the dataset is loaded (PDD §11).
- Tune `fanOutCap` and `sideCap` in §5.2 against real k6 results; both are exposed as configuration, not
  hardcoded, specifically so this tuning doesn't require a code change.
- Decide the `co_star_edges` refresh cadence (PDD §11 open question) if this ever moves beyond a
  single-import deployment.
