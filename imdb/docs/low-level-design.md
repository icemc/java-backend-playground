# IMDb Copycat API - Low-Level Design Document

| | |
|---|---|
| Author | Ludovic Temgoua Abanda |
| Status | Draft |
| Date | 2026-07-06 |
| Related docs | `imdb/docs/product-design.md` (PDD, approved), `imdb/docs/REQUIREMENTS.md` |
| Data source | [`abanda/imdb-postgresql`](https://github.com/icemc/imdb-postgresql) |

## 1. Purpose and Scope

The PDD defines what is being built and why. This document defines how: the onion-layered package
structure, schema additions on top of the seeded database, endpoint contracts, the bidirectional-CTE SQL
for Six Degrees, the caching strategy, docker-compose topology, observability wiring, and the test plan.
The Maven project has been scaffolded and the core layers (§2) are implemented and compiling; what
remains is tracked in §11 (dashboards, k6 scripts, the Dockerfile, and a handful of tuning decisions that
need real data/load-test results to answer responsibly).

## 2. Architecture & Module Layout

### 2.1 Onion layers and the dependency rule

The initial pass at this codebase used a flat package structure (`repository`, `service`, `web`) where
every service directly `new`'d or `@Autowired` concrete JDBC/Redis classes. That worked, but it violated
the Dependency Inversion Principle badly enough to matter: nothing could be tested without a real
Postgres/Redis behind it, and swapping either one - say, to evaluate the Neo4j alternative from PDD §9 -
would have meant editing the use-case classes themselves. This section replaces that with a proper onion
architecture.

**Layers, outermost to innermost**: `presentation` → `infrastructure` → `application` → `domain`, plus a
standalone `utils` layer with no dependencies at all. The dependency rule is enforced by import direction
alone (no build-tool module boundaries yet - see the open item in §11):

| Layer | May import | Contains |
|---|---|---|
| `presentation` | `application`, `infrastructure`, `domain`, `utils` | Controllers, request validation, `ApiExceptionHandler`, `RequestLoggingFilter` |
| `infrastructure` | `application`, `domain`, `utils` | JDBC repository implementations, Redis caching decorators, Spring `@Configuration` |
| `application` | `domain`, `utils` | Use-case interfaces + implementations, use-case-specific result types |
| `domain` | *(nothing)* | Entities/value objects, repository **interfaces**, domain exceptions |
| `utils` | *(nothing)* | `ImdbIds`, `HeaderSanitizer` - pure, framework-free helpers |

Never: `domain` importing `application`/`infrastructure`; `application` importing `infrastructure`. The
second rule is the one that actually matters day to day - application code calls domain interfaces only,
and Spring's IoC container wires in the concrete infrastructure bean at runtime without a single
`application`-package `import` ever naming a JDBC or Redis class.

**Where interfaces earn their keep**: every `domain.repository` interface has exactly one JDBC
implementation today, so on its own that's DIP for its own sake. The payoff shows up in `application`,
where every top-level use case a controller depends on is *also* an interface
(`TitleSearchUseCase`, `TitleDetailUseCase`, `TopRatedUseCase`, `SixDegreesUseCase`), each with a plain
`*Impl` and - where caching applies - an `infrastructure.cache` decorator implementing the same
interface, marked `@Primary`. These four interfaces (plus `SixDegreesOutcome`, the sealed result type of
the `SixDegreesUseCase` contract) live in `application.contracts`, separated from their implementations
in `application` directly - the same "interfaces apart from implementations" convention `domain.repository`
already uses one layer in:

```
application.contracts.TitleSearchUseCase (interface)
  ├─ application.TitleSearchUseCaseImpl               - plain orchestration, no caching
  └─ infrastructure.cache.CachingTitleSearchUseCase    - @Primary, @Cacheable, delegates to the Impl above
```

A controller depends on `TitleSearchUseCase` and never learns which one it got. Swapping cache
technology, or removing caching entirely, is a change to `infrastructure.cache` alone. Swapping Postgres
for something else is a new `infrastructure.persistence` class implementing the same `domain.repository`
interface - `application` and `presentation` are untouched either way. `PersonResolutionUseCase` is the
one exception: it's an internal collaborator used only by `SixDegreesUseCaseImpl`, never injected into a
controller or wrapped by a decorator, so it stays a concrete class - not every class needs an interface,
only the ones that are an actual seam.

**Where caching attaches, and why it isn't uniform**: `CachingTitleSearchUseCase`,
`CachingTitleDetailUseCase`, and `CachingTopRatedUseCase` decorate the **use-case** interfaces.
`CachingCoStarGraphRepository` decorates the **repository** interface instead, one layer further in. The
reason is the shape of the work each one does: `TitleDetailUseCase.getDetail` fans out to five repository
calls and assembles one `TitleDetail` - caching the assembled result in one entry (matching the four
regions in §6) is both truer to the original design and cheaper than five separate repository-level cache
entries per title. `SixDegreesUseCase.compute`, on the other hand, takes raw query strings that might be
names needing disambiguation and a `maxDegree` that varies per request - a poor, fragile cache key.
`CoStarGraphRepository.findShortestPath(int, int)`, one level in, takes two clean integer ids and returns
the *true* shortest distance independent of any of that - exactly the key described in §6. This is also
why the domain interface returns a clean `GraphPath`, not the bidirectional search's raw forward/backward
arrays: see §5.2 for what that cleanup actually removed.

**A caching bug that no longer needs remembering**: the original design's `DistanceCache` component
existed solely to work around a Spring AOP pitfall - `@Cacheable` uses a proxy, and a method calling
another method on `this` bypasses that proxy silently. Now that every cached method lives on a decorator
that is structurally a *different bean* from the thing it wraps, that failure mode isn't possible by
construction. Nobody has to remember not to call a cached method from within its own class, because there
is no such call anywhere in this codebase anymore.

### 2.2 Package layout (current state)

```
imdb/
  pom.xml
  Dockerfile
  docker-compose.yaml
  docker-compose.e2e.yaml           # lightweight plain-postgres stack for the CI e2e stage, §10
  postman/
    imdb-e2e.postman_collection.json  # Newman-run contract tests against the e2e stack, §10
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
      dashboards/json/            # dashboard JSON models, still to author - see §11
  k6/
    search.js                     # still to author - see §11
    title-detail.js
    top-rated.js
    six-degrees.js
    data/sampled-people.csv       # actor pool used by six-degrees.js to defeat caching
  src/
    main/
      java/com/ludovictemgoua/imdb/
        ImdbApplication.java
        utils/
          ImdbIds.java
          HeaderSanitizer.java             # redacts sensitive headers before logging (§7)
        domain/
          model/
            TitleSummary.java
            TitleCore.java
            TitleDetail.java
            RatingView.java
            CastMember.java
            CreditedPerson.java
            GenreTopRatedItem.java
            SharedTitle.java
            PersonCandidate.java
            PagedResult.java
            GraphPath.java
            PersonResolution.java
          repository/
            TitleRepository.java          # interface
            PersonRepository.java         # interface
            CoStarGraphRepository.java    # interface
          exception/
            NotFoundException.java
        application/
          contracts/
            TitleSearchUseCase.java       # interface
            TitleDetailUseCase.java       # interface
            TopRatedUseCase.java          # interface
            SixDegreesUseCase.java        # interface
            SixDegreesOutcome.java        # sealed result type of the SixDegreesUseCase contract
          TitleSearchUseCaseImpl.java
          TitleDetailUseCaseImpl.java
          TopRatedUseCaseImpl.java
          SixDegreesUseCaseImpl.java
          PersonResolutionUseCase.java    # concrete - internal collaborator, not a public seam
          PersonRef.java
          PathStep.java
          SixDegreesResult.java
        infrastructure/
          persistence/
            JdbcTitleRepository.java
            JdbcPersonRepository.java
            JdbcCoStarGraphRepository.java  # thin wrapper - the actual BFS lives in the DB, §5.2-5.3
          cache/
            CachingTitleSearchUseCase.java
            CachingTitleDetailUseCase.java
            CachingTopRatedUseCase.java
            CachingCoStarGraphRepository.java
            CacheConfig.java
        presentation/
          TitleController.java
          GenreController.java
          PersonController.java
          ApiExceptionHandler.java
          RequestLoggingFilter.java        # request id + sanitized request/response logging (§7)
      resources/
        application.yaml
        db/migration/
          V0__base_schema.sql
          V1__extensions_and_search_indexes.sql
          V2__co_star_edges_materialized_view.sql
          V3__shortest_co_star_path_function.sql  # find_shortest_co_star_path PL/pgSQL BFS, §5.2-5.3
          V4__title_principals_nconst_index.sql   # full nconst index - findAnyCommonTitle needs it
                                                   # across all categories, not just the partial
                                                   # acting-only index V1 created
    test/
      java/com/ludovictemgoua/imdb/
        application/                        # unit tests, mocked domain.repository interfaces - §10
        infrastructure/
          persistence/*IntegrationTest.java  # Testcontainers Postgres - §10
          cache/*Test.java                   # unit: mocked delegate, no Spring context - §10
          cache/*IntegrationTest.java        # integration: Testcontainers Redis, real (de)serialization - §10
        presentation/                        # MockMvc controller tests, mocked use cases
        TestcontainersConfiguration.java
      resources/
        fixtures/
          fixture-data.sql                  # single source of truth: integration tests (@Sql) and
                                             # the e2e stage's seed service both load this same file
```

`application.yaml`, not `.properties`: the config surface is more nested than a typical CRUD app -
`management.metrics.*`, `management.tracing.*`, and `management.opentelemetry.*` (§7) sit several levels
deep alongside a custom `six-degrees.*` group (§5/§6), and YAML's multi-document `---` profiles let the
Testcontainers-backed integration tests (§10) override just the datasource block against the fixture
container instead of `docker-compose.yaml`'s Postgres, without duplicating everything else.

## 3. Data Layer

### 3.1 Why plain JDBC, not JPA/Hibernate

Every query this API needs is a hand-tuned native query: trigram similarity search, GIN array containment
for genres, a Bayesian weighted-rating computation, and a recursive CTE for graph traversal. None of these
benefit from Hibernate's object-relational mapping or dirty-checking - there is no object graph being
mutated, only read projections. Introducing JPA here would mean fighting it (native `@Query` everywhere)
for zero benefit. The design instead uses Spring's `NamedParameterJdbcTemplate` with explicit `RowMapper`s
throughout, and Flyway for the migrations below - one data-access style, not two.

This is orthogonal to (and compatible with) the onion architecture in §2: `NamedParameterJdbcTemplate` is
an implementation detail entirely confined to `infrastructure.persistence`. Nothing in `domain` or
`application` knows JDBC exists - they see only the `TitleRepository`/`PersonRepository`/
`CoStarGraphRepository` interfaces in `domain.repository`.

### 3.2 Migration V1: extensions and search indexes

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_title_basics_primary_title_trgm
    ON title_basics USING gin (primary_title gin_trgm_ops);

CREATE INDEX idx_title_basics_original_title_trgm
    ON title_basics USING gin (original_title gin_trgm_ops);

CREATE INDEX idx_title_basics_genres
    ON title_basics USING gin ((genres::text[]));

CREATE INDEX idx_title_ratings_rank
    ON title_ratings (average_rating DESC, num_votes DESC);

CREATE INDEX idx_title_principals_nconst_acting
    ON title_principals (nconst)
    WHERE category IN ('actor', 'actress', 'self');

CREATE INDEX idx_name_basics_primary_name_trgm
    ON name_basics USING gin (primary_name gin_trgm_ops);
```

The `genres` index is built on the **expression** `(genres::text[])`, not the bare column. Production's
`genres` is a Postgres `GENRE` enum array, but the V0 test fixture (§3.4) simplifies it to plain
`TEXT[]`. Casting to `text[]` in the top-rated query (§4.3) lets one query run unmodified against both
schemas - but Postgres only matches a query's cast expression against an index built on that *same*
expression, not against a plain index on the uncast column. Indexing `(genres::text[])` directly (a
no-op cast in the test fixture, a real enum-to-text cast in production) keeps the index usable in both
places instead of silently falling back to a sequential scan in production.

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

### 3.4 Migration V0: base schema for local/test parity (runs *before* V1, documented last)

A gap surfaced while working out the test setup (§10): `V1`/`V2` above only add indexes and a view *on
top of* the seven base tables (`name_basics`, `title_basics`, `title_ratings`, `title_crew`,
`title_principals`, plus the two unused ones). Those base tables are created by the upstream Python
loader baked into `abanda/imdb-postgresql`, not by anything in this project - which is fine for
`docker-compose.yaml`'s Postgres, but a freshly-started Testcontainers `postgres:17` (used by the
integration tests and by the `TestcontainersConfiguration` Spring Initializr already generated) has none
of these tables, and `V1` would fail immediately trying to index them.

The fix is a migration numbered `V0` - Flyway runs migrations in version order regardless of when they
were authored, so `V0__base_schema.sql` runs before `V1` even though it's documented after it here - that
creates the base tables **idempotently**:

```sql
CREATE TABLE IF NOT EXISTS name_basics (
    nconst INTEGER PRIMARY KEY,
    primary_name TEXT NOT NULL,
    birth_year INTEGER,
    death_year INTEGER,
    primary_profession TEXT[],
    known_for_titles INTEGER[]
);

CREATE TABLE IF NOT EXISTS title_basics (
    tconst INTEGER PRIMARY KEY,
    title_type TEXT NOT NULL,
    primary_title TEXT NOT NULL,
    original_title TEXT NOT NULL,
    is_adult BOOLEAN NOT NULL DEFAULT FALSE,
    start_year INTEGER,
    end_year INTEGER,
    runtime_minutes INTEGER,
    genres TEXT[]
);

CREATE TABLE IF NOT EXISTS title_ratings (
    tconst INTEGER PRIMARY KEY REFERENCES title_basics (tconst),
    average_rating NUMERIC NOT NULL,
    num_votes INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS title_crew (
    tconst INTEGER PRIMARY KEY REFERENCES title_basics (tconst),
    directors INTEGER[],
    writers INTEGER[]
);

CREATE TABLE IF NOT EXISTS title_principals (
    tconst INTEGER NOT NULL REFERENCES title_basics (tconst),
    ordering INTEGER NOT NULL,
    nconst INTEGER NOT NULL REFERENCES name_basics (nconst),
    category TEXT NOT NULL,
    job TEXT,
    characters TEXT[],
    PRIMARY KEY (tconst, ordering)
);
```

Two deliberate simplifications versus the real schema, both safe because of how `IF NOT EXISTS` behaves:

- **Enum columns become `TEXT`/`TEXT[]`** (`title_type`, `genres`, `category`) instead of the real
  Postgres `ENUM` types. Our own queries only ever compare these as strings/arrays, so behavior is
  identical for anything this project does; only faithfully replicating the upstream loader's exact enum
  constraints would need the real types, which isn't a goal here.
- **Foreign keys are always enforced here**, whereas the real loader's own comments note its
  `add_references` step isn't consistently applied (PDD §11). Harmless for fixture data we control.
- Against `docker-compose.yaml`'s `abanda/imdb-postgresql` Postgres, every table already exists with the
  real (enum-based) schema, so every `CREATE TABLE IF NOT EXISTS` above is a no-op - `V0` only ever does
  real work against a blank Testcontainers instance.

### 3.5 ID translation at the boundary

`tconst`/`nconst` are stored as plain `INTEGER`. The API never exposes these directly. `ImdbIds` lives in
the standalone `utils` layer (§2.1) - pure string/int parsing, no dependency on anything else in the
codebase, usable from `infrastructure` (mapping JDBC rows) and `application` (formatting ids into result
records) alike:

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
`@RequestParam`/`@PathVariable` converter, before it ever reaches a use case or query.

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
`PagedResult<TitleSummary>` (`domain.model` - a small hand-rolled pagination wrapper, not Spring Data's
`Page`, consistent with §3.1's decision to keep Spring Data out of this codebase entirely) with `id`,
`primaryTitle`, `originalTitle`, `titleType`, `startYear`, `endYear` per item.

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

`404` (via `domain.exception.NotFoundException` -> `ProblemDetail`, thrown by `TitleDetailUseCaseImpl` and
mapped by `presentation.ApiExceptionHandler`) if `tconst` doesn't exist in `title_basics`.

### 4.3 `GET /api/v1/genres/{genre}/top-rated?limit=&minVotes=`

Restricted to `title_type = 'movie'`, ranked by weighted rating rather than raw average (PDD §9):

```sql
WITH pool AS (
    SELECT tb.tconst, tb.primary_title, tb.start_year, tr.average_rating, tr.num_votes
    FROM title_basics tb
    JOIN title_ratings tr ON tr.tconst = tb.tconst
    WHERE tb.title_type = 'movie'
      AND tb.genres::text[] @> ARRAY[:genre]::text[]
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

## 5. Six Degrees: Bidirectional BFS as a PL/pgSQL Function

### 5.1 Why bidirectional, not the naive one-sided walk

A one-sided recursive walk from person A, expanding until person B is found, pays the graph's full
branching factor for every hop - and this graph has extreme hub nodes (some credited actors have
thousands of co-stars; talk-show hosts exceed 8,000). Meeting in the middle from both ends roughly
squares down the search space (`b^(d/2)` instead of `b^d`), and combined with the product's own 7-degree
cap, each side only ever needs to expand `⌈7/2⌉ = 4` hops. See PDD §9 for the full comparison against
precomputed BFS, in-memory BFS, and Pruned Landmark Labeling.

### 5.2 Why this isn't a single recursive CTE anymore

The first working version *was* a single bidirectional recursive CTE (each side's frontier expansion
and cycle check expressed declaratively, path arrays carried through the recursion). It passed review
and its own unit tests, then failed under real data and load in two distinct ways:

1. **Correctness**: fan-out was capped with `ORDER BY person_b LIMIT :fanOutCap`, always keeping the
   same fixed (lowest-id) subset of a hub's neighbors and silently dropping the rest. If the actual
   connecting co-star wasn't in that arbitrary subset, the query reported "no path found" (or a longer
   path) even though a real, shorter path existed. This is a wrong-answer bug, not a slow-answer bug.
2. **Performance**: cycle prevention only checked that a single path didn't revisit its own history
   (`NOT nbr.person_b = ANY(path)`) - there was no *shared* visited set. The same node gets rediscovered
   by many different paths in a small-world co-star graph, and each rediscovery independently
   re-expanded from that node again. A real hub-to-hub query (two talk-show hosts, ~8,000 co-stars each)
   took 3+ minutes and spilled to disk with `fanOutCap=200`/`sideCap=4` - and no fan-out cap small enough
   to avoid that blowup was also large enough to not risk bug 1.

Both bugs share one root cause: a plain recursive CTE has no way to check a candidate node against
*everything already discovered so far* - only against the single path being extended. Fixing that
requires genuine iterative state (a real visited set per side), which SQL's `WITH RECURSIVE` can't
express on its own. The fix keeps this a pure SQL/PL-pgSQL solution (per-endpoint choice - see PDD §9
for algorithms that mix in Java or a graph database instead) by moving it into a PL/pgSQL function:
`find_shortest_co_star_path` (`V3__shortest_co_star_path_function.sql`), called from
`infrastructure.persistence.JdbcCoStarGraphRepository` as a single `SELECT * FROM
find_shortest_co_star_path(:personA, :personB, :sideCap, :absoluteMaxDegree)`. The
`domain.repository.CoStarGraphRepository` interface is unchanged - still exactly one method,
`Optional<GraphPath> findShortestPath(int personA, int personB)` - so this swap was invisible to
`application` and `presentation`.

### 5.3 How the function works

- Two `TEMP TABLE`s (`visited_forward`, `visited_backward`, each `(person, parent)`) hold the real,
  de-duplicated visited set and parent pointer per side - created `IF NOT EXISTS` and `TRUNCATE`d at
  the start of each call rather than `ON COMMIT DROP`, since a caller that runs the function twice
  within one open transaction (e.g. a `@Transactional` test) would otherwise hit "relation already
  exists" on the second call.
- Each iteration expands whichever side currently has the smaller frontier (the standard
  bidirectional-BFS optimization), inserting every neighbor *not already visited on that side* - no
  arbitrary cap, so no real neighbor is ever silently dropped. On a tie, a `v_prefer_forward` flag
  alternates which side wins, flipped after every tie-driven choice - without this, a plain "prefer
  forward on ties" rule starves the backward side completely on any non-branching chain (frontier size
  stays 1 = 1 every iteration), a real bug caught by the `findsTheShortestPathAcrossMultipleHopsOnBoth-
  SidesOfTheBidirectionalSearch` integration test before this shipped.
- After each expansion, only the *newly* discovered nodes are checked against the other side's full
  visited set - the loop exits the instant they intersect, so most real pairs (small-world graph, most
  people connect within 1-2 hops) resolve almost immediately rather than exploring to `sideCap` on both
  sides unconditionally.
- Once a meeting node is found, the path is reconstructed by walking the `parent` pointers from the
  meeting node back to each root - a plain linear parent-chain walk (not the combinatorial multi-path
  search above), so a small recursive CTE is the right, cheap tool for that specific step.
- `:sideCap` = 4 and `:absoluteMaxDegree` = 7 bound the loop exactly as before (§6 for why these are
  fixed independent of the caller's requested `maxDegree`); `personA == personB` short-circuits inside
  the function (degree 0, no expansion at all), mirroring the special case `SixDegreesUseCaseImpl`
  already has above it.
- A query timeout is still set directly on the underlying `JdbcTemplate` inside
  `JdbcCoStarGraphRepository`'s constructor (no declarative `spring.jdbc.template.query-timeout`
  property exists in Boot 4.1) as a last-resort circuit breaker, though early termination on
  intersection means it's rarely exercised in practice now.
- Verified against the exact pathological pair that broke the old query (two ~8,000-co-star hub nodes,
  no direct edge): 29-44ms and a correct degree-2 result, versus 3+ minutes and a disk spill before.

### 5.4 Person resolution

`PersonResolutionUseCase` (application layer, an internal collaborator of `SixDegreesUseCaseImpl` - see
§2.1) resolves a `name` query against `name_basics` via the trigram index from §3.2, through the
`domain.repository.PersonRepository` interface. Exactly one strong match (similarity above a threshold
and no close runner-up) proceeds directly; multiple plausible matches return the disambiguation payload
from §4.4, including `birthYear` and a couple of `knownForTitles` entries per candidate so a human can
tell "Michael J. Fox" from another same-named person at a glance. The result is the sealed
`domain.model.PersonResolution` (`Resolved` / `Ambiguous` / `NotFound`), matched exhaustively via a Java
21 `switch` in `SixDegreesUseCaseImpl`.

## 6. Caching Strategy

Redis, cache-aside, via Spring's `@Cacheable`/`CacheManager` - but unlike a typical Spring tutorial, the
`@Cacheable` annotations never sit on the same classes that contain business logic. Per §2.1, caching is
implemented entirely as `infrastructure.cache` decorators around `application`-layer use-case interfaces
(three of the four regions) or a `domain.repository` interface (the fourth):

| Cache | Decorator | Key | TTL | Notes |
|---|---|---|---|---|
| `title-search` | `CachingTitleSearchUseCase` | `query, page, size` | 24h | Small enough result sets that full-parameter keying is fine |
| `title-detail` | `CachingTitleDetailUseCase` | `titleId` | 24h | One entry for the fully-assembled `TitleDetail`, even though the use case fans out to five repository calls to build it |
| `top-rated` | `CachingTopRatedUseCase` | `genre, limit, minVotes` | 24h | |
| `six-degrees` | `CachingCoStarGraphRepository` | `min(personA,personB)-max(personA,personB)` (**not** including `maxDegree`) | 24h | Stores the true shortest distance up to the absolute 7-degree cap; a request with a smaller `maxDegree` is served from the same cache entry and simply reports "beyond requested max" without recomputation (PDD §9). Cached at the repository level, not the use-case level - see §2.1 for why this one region breaks the pattern of the other three. |

All TTLs are long because the underlying dataset only changes when the Docker image is reloaded - there
is no write path invalidating these entries mid-flight. A full `FLUSHDB` on redeploy is the accepted
invalidation strategy, documented rather than automated, since there's no signal in the running system
that would tell it the data changed underneath it.

Each decorator is a Spring bean implementing the same interface as its plain counterpart, annotated
`@Primary` so it's what gets autowired everywhere the interface is requested; its constructor takes the
*concrete* plain implementation class (e.g. `CachingTitleSearchUseCase(TitleSearchUseCaseImpl delegate)`),
which is the one place `infrastructure` code names a specific implementation class rather than an
interface - unavoidable, since something has to construct the delegate unambiguously, and it's a
same-layer (`infrastructure` → `infrastructure`, effectively) reference, not a boundary violation.

**Jackson 3, not Jackson 2**: `CacheConfig` serializes cache values with
`org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer`, not the more commonly
documented `GenericJackson2JsonRedisSerializer`. Boot 4.1's default Jackson is Jackson 3
(`tools.jackson.core`/`tools.jackson.databind` - a different Maven groupId and root package than Jackson
2.x's `com.fasterxml.jackson.*`, not just a version bump), and `spring-boot-starter-webmvc` doesn't pull
in a Jackson implementation at all by default anymore - `spring-boot-starter-jackson` has to be added
explicitly (present in `pom.xml`; missing it produces a `NoClassDefFoundError` for Jackson types, not a
compile error, since nothing in this codebase references Jackson classes directly - Spring's own
autoconfiguration and `GenericJacksonJsonRedisSerializer` are the only things that touch it). The "2"
variant fails at runtime with `ClassNotFoundException: com.fasterxml.jackson.databind...` since that
package genuinely isn't on the classpath. `enableSpringCacheNullValueSupport()` also has to be requested
explicitly on the Jackson 3 serializer's builder - it was on by default on the old serializer's
constructor - which matters here since caching a "no path found" result as `null` is deliberate, not
incidental.

**Load-test interaction** (carried over from the PDD discussion): the `six-degrees` k6 script must draw a
different, pre-sampled person pair per iteration specifically so it exercises the bidirectional CTE
instead of just measuring a warm Redis round-trip after the first request - see §8.

## 7. Observability Wiring

- **Metrics**: `micrometer-registry-prometheus`. Default HTTP (`http.server.requests`, percentile-histogram
  enabled - §7.1) and JVM/process metrics come from Boot's own auto-configuration; there is no separate
  custom `Timer` for the six-degrees CTE's DB-query phase specifically - `uri`-filtering the standard
  `http_server_requests_seconds` histogram (six-degrees latency dashboard, §7.1) already isolates it from
  the other three endpoints without needing one, since this API has exactly one repository call per
  request on the hot path anyway. Cache hit/miss/put `Counter`s per region are manually bound
  (`infrastructure.cache.CacheConfig.cacheStatisticsMeterBinder`, §7.1) - Boot 4.1 removed the
  auto-binding this design originally assumed.
- **Tracing**: the `spring-boot-starter-opentelemetry` starter (Boot 4.1's unified tracing starter -
  supersedes the Boot 3-era `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` combo)
  exports to Tempo via `management.opentelemetry.tracing.export.otlp.endpoint=http://tempo:4318/v1/traces`
  - note this property lives under `management.opentelemetry.*`, not the old `management.otlp.*`
  namespace, matching `docker-compose.yaml`. `management.tracing.sampling.probability=1.0` for this
  exercise (full sampling; would be tuned down in a real production deployment under real traffic volume).
- **Logging**: structured JSON via Spring Boot 4.1's native structured logging (`logging.structured.
  format.console: logstash` - confirmed present directly in the spring-boot-4.1.0 jar,
  `org.springframework.boot.logging.structured.*`; no `logstash-logback-encoder` or other dependency
  needed), shipped to Loki via Grafana Alloy (already wired in `docker-compose.yaml` /
  `observability/alloy/config.alloy`). "logstash" format chosen over the built-in ECS/GELF
  alternatives since it's schema-agnostic and Loki doesn't care about a specific schema, unlike ECS
  (Elastic-specific) or GELF (Graylog-specific).
  - **Correlation IDs**: every MDC entry is automatically included in each JSON line, which is what
    carries two independent identifiers with no per-log-statement wiring: `traceId`/`spanId` (already
    populated by Micrometer Tracing whenever a span is active) and `requestId` - a dedicated id
    assigned by `presentation.RequestLoggingFilter`, deliberately independent of tracing so it stays a
    reliable per-request identifier even if trace sampling is later turned down from today's 100%
    (`management.tracing.sampling.probability`). The filter honors an incoming `X-Request-Id` header
    if the caller already generated one, otherwise generates a UUID; echoes it back as a response
    header; and clears it from MDC in a `finally` block so it can't leak onto the next request handled
    by the same pooled thread. Runs at `Ordered.HIGHEST_PRECEDENCE` so the id is set as early as
    possible in the chain.
  - **Sanitizer**: `utils.HeaderSanitizer` redacts a deny-list (`Authorization`, `Cookie`,
    `Set-Cookie`, `X-Api-Key`) before `RequestLoggingFilter` logs request headers. This API has no
    auth today, so nothing sensitive flows through yet - the point is that logging is already correct
    the moment auth (or any header carrying a secret) is added, not something to retrofit later.
    Framework-agnostic by design (operates on a plain `Map<String, String>`, not
    `HttpServletRequest`) so it stays usable from `utils` without pulling in the Servlet API.
  - **Level policy** (`logging.level.com.ludovictemgoua.imdb`, default `INFO`, overridable per
    environment via the standard `LOGGING_LEVEL_COM_LUDOVICTEMGOUA_IMDB` env var with no code change):
    INFO for request start/end (`RequestLoggingFilter`) and business-significant outcomes worth their
    own line beyond the request summary (`SixDegreesUseCaseImpl` - person-resolution ambiguity, and
    the computed degree/timing, since this is the one use case with real, previously-invisible
    performance variance all session); DEBUG for query params/row counts in
    `infrastructure.persistence` and cache-miss events in `infrastructure.cache` (`@Cacheable` only
    invokes the annotated method on a miss, so a log statement inside it is inherently a miss signal -
    aggregate hit ratio is already tracked via Micrometer's cache metrics, the Cache Hit Ratio
    dashboard); WARN for `find_shortest_co_star_path` calls over 1 second
    (`JdbcCoStarGraphRepository`); DEBUG for expected 4xx conditions and ERROR (with the full
    exception) for anything unhandled reaching `ApiExceptionHandler`'s catch-all, so an unexpected
    failure is never silently reduced to a bare 500 with no trace of the real cause.
- **Correlation**: `observability/grafana/provisioning/datasources/datasources.yml` already wires
  Loki-derived-fields -> Tempo and Tempo -> Loki/Prometheus, so a trace opened in Grafana click-throughs to
  its log lines and vice versa.
- **Dashboards**: provisioned via `observability/grafana/provisioning/dashboards/dashboards.yml`, pointing
  at a `dashboards/json/` folder. Actual dashboard JSON models (HTTP overview, six-degrees CTE latency
  breakdown, cache hit ratio, k6 load-test results) are authored during implementation, not shipped as
  part of this design (see §11).

### 7.1 Making the dashboards actually work: four real bugs, found by querying Prometheus directly

All four dashboards were authored against assumed metric shapes and shipped without a live scrape ever
confirming them (§11's own caveat on the cache dashboard: "not confirmed against a live scrape at
authoring time"). Once actually queried against a running stack, every dashboard except three panels of
HTTP Overview turned out to be empty - not from a dashboard-JSON bug in most cases, but because the
metrics themselves didn't exist yet. Fixed in order of discovery:

1. **No `_bucket` series at all, anywhere** - every `histogram_quantile(...)` panel (HTTP Overview's p95,
   all three Six Degrees Latency panels) was empty because Micrometer only emits a Timer's `_count`/`_sum`/
   `_max`, never `_bucket`, unless percentile histograms are explicitly turned on. `application.yaml` never
   set this. Fix: `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.
   Verified: `http_server_requests_seconds_bucket` appears in `/actuator/prometheus` immediately after
   restart, and `histogram_quantile(0.95, ...)` returns real numbers once the target `uri` has recent
   traffic in the query window (a `uri` with none still returns `NaN` - expected Prometheus behavior for
   an all-zero-rate histogram, not a bug).
2. **`cache_gets_total`/`cache_puts_total` never existed at all** - not a naming or label mismatch, the
   metrics were simply never registered. Root cause, confirmed by decompiling the actual jars rather than
   trusting memory of older Boot versions: `spring-boot-actuator-autoconfigure-4.1.0.jar` contains no
   `cache` package whatsoever - the `CacheMetricsRegistrar`/`CacheMeterBinderProvider` auto-binding this
   design assumed (a real feature in Boot 2/3) does not exist in Boot 4.1. Fix, replicated manually in
   `CacheConfig` rather than reintroducing Boot's removed mechanism: build the `RedisCacheManager`'s
   `RedisCacheWriter` explicitly with `.collectStatistics()` (Spring Data Redis's own, independent
   `CacheStatisticsCollector` - unaffected by the Boot-side removal), and register a `MeterBinder` bean
   that exposes each region's `getHits()`/`getMisses()`/`getPuts()` as `FunctionCounter`s named
   `cache.gets`/`cache.puts`, tagged `cache`/`result` to match the dashboard's existing (correctly
   anticipated) query shape exactly. `initialCacheNames(...)` on the manager builder (added alongside this
   fix) makes all four regions exist from startup rather than being created lazily on first use.
   Verified: exercising each cached endpoint exactly twice produces precisely 1 miss + 1 hit + 1 put per
   region in `/actuator/prometheus`.
3. **Prometheus's own remote-write receiver was never actually on** - `docker-compose.yaml` passed
   `--enable-feature=remote-write-receiver`, which was never a valid `--enable-feature` value; Prometheus
   3.11.3's own startup log said so outright ("Unknown option for --enable-feature"), and the write
   endpoint 404'd accordingly. The correct, and long-standing, flag is the dedicated
   `--web.enable-remote-write-receiver` (confirmed against `prometheus --help` directly). This is why the
   k6 dashboard had zero data even before its query-shape bugs (below) mattered at all.
4. **k6's `experimental-prometheus-rw` output doesn't emit what the dashboard assumed**, on two counts,
   both confirmed by actually running `k6 run --out experimental-prometheus-rw` against the live stack and
   inspecting exactly what landed in Prometheus:
   - `K6_PROMETHEUS_RW_SERVER_URL` alone does not activate the output - a run with only that env var set
     completed with no errors and pushed nothing. `K6_OUT: experimental-prometheus-rw` (the env-var
     equivalent of `k6 run --out experimental-prometheus-rw`) is now set directly on the `k6` service in
     `docker-compose.yaml`, so the plain documented invocation (`docker compose --profile load-test run k6
     run /scripts/<name>.js`, §8) works without anyone needing to type that flag by hand.
   - There is no `k6_http_req_duration_seconds_bucket` or `k6_http_req_failed_total` - k6's trend metrics
     (`http_req_duration`, etc.) are exported as pre-computed percentile *gauges* per request URL
     (`k6_http_req_duration_p99{name=...}` by default - `p99` only, until
     `K6_PROMETHEUS_RW_TREND_STATS: "p(95),p(99)"` is set to also get `_p95`; the bare form `"p95"` is
     rejected at k6 startup with "invalid trend stat", confirmed empirically - it has to be k6's own
     `p(95)` threshold syntax), and `http_req_failed` - a k6 "rate" metric (a 0..1 boolean average, not a
     counter) - only ever exists as `k6_http_req_failed_rate`, never a `_total` counter. Fixed
     `k6-load-test.json`'s two affected panels to query `avg(k6_http_req_duration_p95)` and
     `avg(k6_http_req_failed_rate)` respectively (averaged across the per-URL series, since `search.js`
     deliberately spreads requests across many distinct query terms - §8), with the failed-rate panel's
     unit corrected from `reqps` to `percentunit` to match.

All four dashboards were re-verified after these fixes: queried every panel's exact PromQL expression
directly against Prometheus (both directly and through Grafana's own datasource-proxy API, to rule out a
Grafana-side provisioning-cache issue) with real traffic generated live (`curl` loops for the HTTP/cache
panels, an actual short `k6 run` for the k6 panels), and confirmed real, non-`NaN`, non-empty values back
for every one of them.

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

- All errors are RFC 7807 `ProblemDetail`: `404` for unknown IDs (`domain.exception.NotFoundException`,
  thrown by `application` use cases, mapped by `presentation.ApiExceptionHandler`), `400` for malformed
  IDs (`IllegalArgumentException` from `utils.ImdbIds`) / out-of-range `maxDegree` (handled automatically
  by Spring MVC's built-in `HandlerMethodValidationException` support, no custom handler needed) /
  missing required query params.
- Six-degrees disambiguation is `200`, not an error status - see §4.4.
- Pagination via the hand-rolled `PagedResult<T>` (§4.1), not Spring Data's `Page`.
- Swagger/OpenAPI UI is deferred - `springdoc-openapi` isn't yet available for Boot 4.1 (§11).

## 10. Test Plan

The seeded `abanda/imdb-postgresql` image is ~20GB and takes 20-30 minutes to import - unusable as a CI
dependency. Testing splits into three tiers - unit, integration, e2e/contract - run as three sequential
jobs in `.github/workflows/imdb-ci.yml` (`unit` -> `integration` -> `e2e`, each gated on the previous one
passing), and the onion split from §2 changes *what* "unit test" means here for the better: use-case
implementations are tested against mocked `domain.repository` **interfaces** with plain Mockito, no Spring
context and no Testcontainers required at all.

### 10.1 Unit tests (Maven Surefire, `mvn test`)

- **Use-case tests**: `application`-layer `*UseCaseImpl` classes against mocked `domain.repository`
  interfaces (JUnit 5 + Mockito + AssertJ, matching `votee`'s existing testing style in this monorepo).
  Because these are pure interface mocks, none of these tests touch Postgres, Redis, or Spring at all.
- **Decorator unit tests** (`infrastructure.cache.Caching*Test`): verify each decorator delegates on a
  cache miss and doesn't re-invoke the delegate on a hit, against a mocked delegate with a plain
  `ConcurrentMapCacheManager` (or an outright mock `Cache`) - no Spring context, no real Redis. Previously
  this reasoning needed care (the old `DistanceCache` design in §2.1 had a self-invocation pitfall to
  avoid); now it's a simple, mechanical test. **What this tier structurally cannot catch**: an in-memory
  map never serializes anything, so a real Redis (de)serialization bug - e.g. the `GenericJacksonJsonRedis-
  Serializer` type-metadata `ClassCastException` this project hit earlier - passes every one of these tests
  while still breaking in production. That gap is closed in §10.2, not here.
- **Controller tests**: `MockMvc` against mocked `application`-layer use-case interfaces (`@MockitoBean` -
  the current replacement for the older `@MockBean`, checked against the Boot 4.1 testing reference docs
  directly), covering request validation and `ProblemDetail` error-shape contracts.

Classified by naming convention, not by directory: `maven-surefire-plugin` is configured in `pom.xml` to
exclude `**/*IntegrationTest.java` and `**/ImdbApplicationTests.java` (the Testcontainers-backed Spring
Boot smoke test), so everything else under `src/test/java` runs here by default - no file relocation
needed, matching the existing `*IntegrationTest.java` suffix convention already in place before this work.

### 10.2 Integration tests (Maven Failsafe, `mvn failsafe:integration-test failsafe:verify`)

- **Persistence integration tests** (`infrastructure.persistence.*IntegrationTest`): Testcontainers
  running a plain `postgres:17` image, exercising the `infrastructure.persistence` implementations
  directly against their `domain.repository` interface contracts. Flyway runs `V0`/`V1`/`V2` from §3
  automatically on context startup (`V0` is what actually creates the base tables here - see §3.4), then
  the shared `fixture-data.sql` (loaded via `@Sql` on the test class, which runs after the context - and
  therefore Flyway - is already up) seeds rows covering: a known multi-hop co-star chain (to exercise the
  bidirectional CTE end-to-end, including a deliberate case where the true path requires more than one hop
  on each side), a tied weighted-rating case, and an ambiguous shared name. Fast and deterministic.
- **Cache integration tests** (`infrastructure.cache.Caching*IntegrationTest`, one per decorator): the
  same four decorators as §10.1, but wired against a **real Redis** via Testcontainers
  (`TestcontainersConfiguration`) and a real `RedisCacheManager`, not `ConcurrentMapCacheManager`. Each
  test calls the decorated method twice inside a `@Transactional @Sql("/fixtures/fixture-data.sql")`
  context, asserts the second call's result equals the first, and asserts the entry is actually present in
  the named cache region under its documented key (§6) via `CacheManager.getCache(region).get(key)`. This
  tier exists specifically because §10.1's mock-backed decorator tests cannot exercise real serialization -
  a `RedisCacheManager` is the only thing in this test plan that actually round-trips a value through
  `GenericJacksonJsonRedisSerializer` the way production does. Four tests, one per region: `title-search`
  (`search("Few Good Men", 0, 20)`), `title-detail` (`getDetail("tt0000100")`), `top-rated`
  (`findTopRated("Action", 10, 100)`), `six-degrees` (`findShortestPath(1, 2)`, keyed `"1-2"` per §6's
  `min-max` convention).
- `ImdbApplicationTests` (the Spring Boot context-loads smoke test, Testcontainers-backed) also runs here,
  alongside the two tiers above - it doesn't match the `*IntegrationTest.java` suffix, so it's named
  explicitly in both the Surefire exclude and the Failsafe include.

`maven-failsafe-plugin` is configured with the mirror-image `<includes>` (`**/*IntegrationTest.java`,
`**/ImdbApplicationTests.java`) and bound to the `integration-test`/`verify` goals; `spring-boot-starter-
parent` manages both plugins' versions, so no explicit `<version>` is pinned in `pom.xml` for either.

### 10.3 E2E / contract tests (Postman + Newman, against `docker-compose.e2e.yaml`)

A third tier below Testcontainers: a fully-built `imdb-service` Docker image, talking to real (if
minimally-seeded) Postgres and Redis containers over the network, hit with real HTTP requests - the only
tier that exercises the actual `Dockerfile` image and the full request path end-to-end, including the
`RequestLoggingFilter`/`ApiExceptionHandler` wiring from §7/§9.

- **Stack**: `docker-compose.e2e.yaml`, a deliberately separate file from the main `docker-compose.yaml`
  (own `name: imdb-e2e` and `imdb-e2e-net` network - see the file's own header comment for the project-name
  collision this guards against). `postgres` is plain `postgres:17`, not `abanda/imdb-postgresql` - the
  real image's 20-30 minute import is fine for local dev, not for a stage that runs on every push. Schema
  is built the same way the integration tests build it: `imdb-service`'s own Flyway migrations
  (`V0`-`V4`), not a hand-copied schema dump.
- **Seed**: a one-shot `seed` service (plain `postgres:17` image as a `psql` client, `depends_on:
  imdb-service: condition: service_healthy` so Flyway has already run) loads the *same*
  `src/test/resources/fixtures/fixture-data.sql` the integration tests use via a read-only bind mount -
  one fixture dataset, not two to keep in sync, per the design discussion that settled on this over
  hand-authoring a separate e2e-only dataset.
- **Contract tests**: `imdb/postman/imdb-e2e.postman_collection.json`, a Postman Collection v2.1 with
  inline `pm.test`/`pm.expect` assertions, run via `npx --yes newman run ... --env-var
  baseUrl=http://localhost:8080` (no separate npm install needed - GitHub-hosted runners ship Node.js).
  Nine requests covering every endpoint in §4 plus their documented edge cases: health, fuzzy search,
  title detail (found and 404), top-rated (weighted-rating ordering, not raw average), six-degrees direct
  / multi-hop / no-path / ambiguous-name. Verified locally: 22/22 assertions passing.
- **CI orchestration** (`imdb-ci.yml`'s `e2e` job): bring the stack up with `-p imdb-e2e` (matching the
  compose file's own `name:`), poll `/actuator/health` until healthy, poll `docker inspect` on the seed
  container until it exits (failing loudly with its logs if the exit code is non-zero), run Newman, dump
  `docker compose logs` on failure, and always tear the stack down (`down -v`) regardless of outcome.

### 10.4 CI summary

| Job | Command | Depends on |
|---|---|---|
| `unit` | `mvn -B test` | - |
| `integration` | `mvn -B failsafe:integration-test failsafe:verify` | `unit` |
| `e2e` | compose up -> health/seed poll -> `newman run` -> teardown | `integration` |

Each job fails fast for the ones after it (`needs:` in GitHub Actions), so a broken unit test never pays
for a Docker build, and a broken integration test never pays for the e2e stack's bring-up time.

- **Load tests**: k6 (§8), run manually against the fully-seeded, production-image stack, not part of the
  CI gate - a different concern (performance under load) from the correctness tiers above.

## 11. Open Items for Implementation

The Maven project itself (Java 21, Spring Boot 4.1.0, group `com.ludovictemgoua`, artifact `imdb`) is
scaffolded and the dependency set below is already in `pom.xml`, generated directly from Initializr with
one post-generation addition needed (`spring-boot-starter-jackson`, see below):

| Dependency (Initializr label) | Include? | Why |
|---|---|---|
| Spring Web | Yes | REST controllers |
| JDBC API | Yes | `NamedParameterJdbcTemplate` access - **not** Spring Data JDBC, see §3.1 (avoids reintroducing a repository/entity abstraction) |
| Jackson | Added post-generation | Not selected on Initializr because it wasn't obviously needed - but `spring-boot-starter-webmvc` doesn't transitively pull in a Jackson implementation on Boot 4.1 the way `spring-boot-starter-web` did on Boot 3, so JSON responses and `GenericJacksonJsonRedisSerializer` (§6) both need it added explicitly |
| PostgreSQL Driver | Yes | Connects to `abanda/imdb-postgresql` |
| Flyway Migration | Yes | Runs the `V0`/`V1`/`V2` migrations from §3 |
| Spring Data Redis | Yes | Cache-aside layer, §6 |
| Validation | Yes | `maxDegree` range / malformed-ID checks at the controller boundary |
| Spring Boot Actuator | Yes | Health, `/actuator/prometheus` |
| Prometheus | Yes | Micrometer -> Prometheus format, scraped by the `prometheus` compose service |
| Distributed Tracing | Yes | Span/trace IDs in logs - log<->trace correlation, §7 |
| OpenTelemetry | Yes | Publishes traces via OTLP to Tempo, pairs with Distributed Tracing - both selections resolve to the single unified `spring-boot-starter-opentelemetry` artifact on Boot 4.1 |
| springdoc-openapi | No | Its Initializr `versionRange` is `[3.5.0.RELEASE, 4.1.0.M1)` - the exact same cutoff as `datasource-micrometer` below, i.e. not yet ported to Boot 4.1. Swagger UI is deferred to whenever springdoc catches up (see PDD §12, Out of Scope); it isn't load-bearing for anything else in this design. |
| Testcontainers | Yes | Backs the integration-test plan, §10 |
| datasource-micrometer | No | Its Initializr `versionRange` is `[3.5.0.RELEASE, 4.1.0.M1)` - not yet ported to the Boot 4.1 line. The gap is covered anyway: Spring Boot auto-binds HikariCP connection-pool metrics via Actuator/Micrometer with no extra dependency, and the one query worth watching closely (the six-degrees CTE) already gets a hand-rolled timeout per §5.2. |
| Lombok | No | This monorepo uses Java records for value types (see `votee`), not Lombok-generated boilerplate |
| Docker Compose (dev-tool) | No | Conflicts with our hand-authored `docker-compose.yaml`, which already includes the app itself as a service |
| Zipkin | No | Redundant trace backend - traces already go via OpenTelemetry/OTLP to Tempo |
| otlp-metrics | No | Redundant metrics path - metrics already go via Prometheus pull-scrape |

Done, verified against a real running stack (not just written and assumed correct):

- **`Dockerfile`**: multi-stage build (Maven build stage -> JRE runtime stage), confirmed with a real
  `docker build` (549MB final image, no errors).
- **Test suite** (§10): 32 unit tests (Surefire: use-case, decorator-unit, controller) and 22 integration
  tests (Failsafe: persistence, the 4 Redis-Testcontainers cache tests, and the Spring Boot smoke test),
  all passing - plus the e2e tier's 9-request/22-assertion Postman/Newman collection, verified against a
  live `docker-compose.e2e.yaml` stack. All three tiers wired into `imdb-ci.yml` as sequential
  `unit` -> `integration` -> `e2e` jobs.
- **Grafana dashboards** under `observability/grafana/provisioning/dashboards/json/` - four dashboards
  (HTTP overview, six-degrees latency breakdown, cache hit ratio, k6 load-test results), confirmed by
  actually bringing up Grafana and checking the provisioned dashboards via its API (`/api/search`,
  `/api/dashboards/uid/...`), and - going further than schema-correctness - by generating real traffic
  and confirming every panel's exact PromQL expression returns real, non-empty data through Prometheus
  and through Grafana's own datasource-proxy API. Four real bugs surfaced by that verification and were
  fixed (missing percentile-histogram config, Boot 4.1 having quietly dropped automatic cache-metrics
  binding entirely, a Prometheus flag that was never valid, and wrong assumptions about k6's remote-write
  metric shapes) - full writeup in §7.1.
- **k6 scripts and `data/sampled-people.csv`** under `imdb/k6/` - `title-detail.js` and `six-degrees.js`
  discover real ids/people from the live API at `setup()` time rather than shipping a
  dataset-snapshot-specific list of hardcoded ids (a first attempt at hardcoding a few "well-known" real
  IMDb ids for the CSV turned out to be exactly the kind of unverified claim worth avoiding - see
  `data/generate-sampled-people.sql` for how to build a proper large sample once real data is loaded).
  Syntax and control flow verified with real `k6 run` invocations (the setup functions' own error
  messages fired exactly as designed against no server).
- **Two real bugs surfaced by actually running the stack, not by review**:
  1. `abanda/imdb-postgresql` keeps bouncing its own Postgres listener throughout the entire ~20-30 minute
     background import (config tuning at startup, and further blips under the heavy `COPY` load) - well
     after the container healthcheck has reported healthy once. `depends_on: condition: service_healthy`
     only gates the *first* startup attempt, not ongoing availability. Fixed at the right layer - Flyway's
     own `connect-retries`/`connect-retries-interval` (§ above, `application.yaml`) so the connection
     attempt itself retries for up to 30 minutes, rather than the whole Spring context failing once and
     needing an external restart.
  2. `imdb-service` had no restart policy at all in `docker-compose.yaml`; added `restart: on-failure:5`
     as a second line of defense on top of the Flyway fix, so a genuinely bad startup still self-heals
     instead of sitting crashed until someone notices.

Still open - these need the real, fully-imported dataset, which takes 20-30 minutes and is a deliberate
choice to run, not something to trigger incidentally:

- Pick the concrete `minVotes` default for the weighted-rating formula from the real vote-count
  distribution once the dataset is loaded (PDD §11).
- Tune `sideCap` in §5.3 against real k6 results if it's ever found to be too conservative or too
  loose - `fanOutCap` no longer exists (§5.2: the BFS rewrite has a real visited set, so it never needs
  to arbitrarily drop neighbors to bound the search).
- Decide the `co_star_edges` refresh cadence (PDD §11 open question) if this ever moves beyond a
  single-import deployment.
- The onion layering in §2 is enforced today only by convention (import direction) and code review, not
  by a build-tool boundary. If this project grows past its current size, splitting `domain`/`application`/
  `infrastructure`/`presentation` into separate Maven modules would make the dependency rule
  compiler-enforced instead of convention-enforced - not done now since a 4-module split is disproportionate
  to this project's actual size, but the package structure is already shaped to make that split
  mechanical later if it's ever worth it.
