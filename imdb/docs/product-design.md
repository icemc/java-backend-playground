# IMDb Copycat API - Product Design Document

| | |
|---|---|
| **Author** | Ludovic Temgoua Abanda |
| **Status** | Draft |
| **Date** | 2026-07-05 |
| **Related docs** | `imdb/docs/low-level-design.md` (follow-up), `imdb/docs/REQUIREMENTS.md` (requirements + concrete setup) |
| **Data source** | [`abanda/imdb-postgresql`](https://github.com/icemc/imdb-postgresql) (full IMDb Non-Commercial Dataset in PostgreSQL 17) |

## 1. Overview

`imdb` is a production-shaped Spring Boot REST API over the IMDb Non-Commercial Dataset, covering title
search with cast/crew, top-rated movies by genre, and the "Six Degrees of Kevin Bacon" graph problem. Like
`votee` (see `votee/docs/product-design.md`), this is a revision exercise as part of a deliberate return
to the Java ecosystem (see the [root README](../../README.md)) - the point isn't just to satisfy the three
functional requirements, but to do so the way a real production service would: proper indexing instead of
full table scans, a defensible algorithm choice for the graph problem instead of "whatever is simplest,"
caching, and full observability (metrics/logs/traces) rather than bolt-on logging.

This document defines *what* is being built and *why*. The follow-up Low-Level Design document defines
*how* - schema mapping, endpoint contracts, the recursive-CTE SQL, docker-compose topology, and the test
plan.

## 2. Background

A common approach to sourcing the IMDb dataset locally truncates it (e.g. dropping `tvEpisodes`) to speed
up import - but the requirements (`imdb/docs/REQUIREMENTS.md`) deliberately call for working against the
real, untruncated data volume, since that's where indexing and algorithm choices actually get tested. This
implementation uses a personal Docker image, `abanda/imdb-postgresql`, which loads the **full** dataset
(including TV episodes) across seven tables: `name_basics`, `title_basics`, `title_ratings`, `title_crew`, `title_episode`,
`title_principals`, `title_akas`. A notable schema detail that shapes several downstream decisions: IDs
are stored as plain `INTEGER`, not IMDb's public `tt`/`nm` string format - the loader strips the prefix
and leading zeros on import (`tt0111161` -> `111161`). No indexes beyond primary keys exist out of the
box, and referential integrity between `title_principals` and `name_basics`/`title_basics` is not
enforced by the loader.

The three requirements sit on the same underlying co-occurrence data (people, titles, and their
relationships) but stress genuinely different parts of the stack: requirement 1 is a search/read problem,
requirement 2 is a ranking problem, and requirement 3 is a graph-traversal problem at a scale (millions of
people, tens of millions of co-star edges) where the naive approach breaks down under load. That last
point is why this document spends real space on comparing algorithms rather than just picking one.

## 3. Goals

- Three read endpoints (plus one supporting search endpoint) that fully satisfy the three functional
  requirements against the real, unmodified dataset - no synthetic subset, per the requirements' own
  guideline against truncating data.
- A **defensible, literature-grounded** algorithm choice for Six Degrees of Kevin Bacon that holds up under
  load for arbitrary person-to-person queries (not just "distance to Kevin Bacon" - see §9), bounded to a
  configurable maximum of 7 degrees.
- Full observability: metrics, structured logs, and distributed traces correlated in a single Grafana
  instance, not just `println`-style logging.
- A demonstrated, load-tested understanding of where this design's performance risk actually lives (the
  graph-traversal endpoint), backed by k6 results per endpoint.
- A fully containerized local environment (`docker-compose up`) that brings up the database, cache,
  application, and the entire observability stack with no manual setup steps beyond that one command.

## 4. Non-Goals

- Authentication/authorization. This is a read-only public API for the purposes of this exercise; see §12.
- Write endpoints of any kind. The dataset is seeded once via the Docker image; there is nothing to
  mutate.
- General-purpose graph database infrastructure (e.g. Neo4j) as a *running* dependency. It's evaluated and
  documented as an alternative in §9 but not built, since it isn't justified at this dataset's scale.
- A UI. This is an API-only deliverable, consistent with the requirements' "endpoint" framing.
- Exact production-grade horizontal scalability (sharding, read replicas, multi-region). The design is
  production-*shaped* - correct indexing, caching, observability, load-tested - but scoped to a
  single-node deployment appropriate for a revision project.

## 5. Target Users & Use Cases

A single consumer persona: a client application (or a developer exercising the API directly via HTTP)
wanting to:
look up a movie by title and see who was in it and who made it; find the best-reviewed movies in a genre;
and find out how closely connected two people in the film industry are - the classic "Six Degrees" party
game, generalized to any two people rather than fixed to Kevin Bacon.

## 6. Functional Requirements

### 6.1 Data model (mapped from the seeded schema)

| Table | Responsibility |
|---|---|
| `title_basics` | Core title data: type, primary/original title, years, runtime, genres |
| `title_ratings` | Average rating and vote count per title |
| `title_crew` | Directors and writers per title |
| `title_principals` | Cast/crew billing per title (person, ordering, category, job, character) |
| `title_episode` | TV episode -> parent series linkage (out of scope for this pass; see §12) |
| `title_akas` | Localized alternate titles (out of scope for this pass; see §12) |
| `name_basics` | Person data: name, birth/death year, professions, known-for titles |

None of these are modified by the application. Additions on top (indexes, a materialized co-star-edge
view) are described in the low-level design.

### 6.2 Endpoints

| # | Endpoint | Requirement | Summary |
|---|---|---|---|
| 1 | `GET /api/v1/titles/search` | #1 | Fuzzy search by primary or original title; paginated list of lightweight matches |
| 2 | `GET /api/v1/titles/{titleId}` | #1 | Full title detail: metadata, rating, directors/writers, top-billed cast |
| 3 | `GET /api/v1/genres/{genre}/top-rated` | #2 | Top-rated movies in a genre, ranked by weighted (Bayesian) rating, not raw average |
| 4 | `GET /api/v1/people/six-degrees` | #3 | Degree of separation between any two people (name or ID), bounded to a configurable max (default and hard cap: 7) |

Endpoint 2 exists because requirement 1 explicitly asks for "related information...including cast and
crew," which doesn't fit in a search-result list item without making every search response expensive; a
search-then-detail split is the standard REST shape for this. Full contracts (request/response DTOs,
error shapes) are in the low-level design, §5.

## 7. Non-Functional Requirements

- **Correctness under load, not just correctness**: every endpoint must remain within its latency
  threshold under the k6 load profiles in the low-level design, not just return the right answer for a
  single request.
- **No unbounded queries**: search is paginated; top-rated has a `limit`; six-degrees has a hard-capped
  traversal depth. Nothing in this API can trigger an unbounded table scan or unbounded graph walk.
- **Read-mostly caching**: since the underlying data only changes when the Docker image is reloaded (no
  write path), aggressive Redis caching is a legitimate default, not a premature optimization.
- **Observability by default**: every request is traceable end-to-end (HTTP -> service -> DB/cache) via
  correlated metrics, logs, and traces - not added after the fact.
- **ID stability at the API boundary**: the API exposes IMDb-style `tt`/`nm` string IDs, never the
  internal integer PKs, so the public contract doesn't leak a storage detail that's specific to this one
  Docker image's schema choices.

## 8. High-Level Architecture

- **Application**: Spring Boot 3, Java 21, Maven (consistent with `votee`'s build-tool choice elsewhere in
  this monorepo).
- **Database**: PostgreSQL 17 via `abanda/imdb-postgresql`, read-only from the application's perspective.
- **Cache**: Redis, cache-aside pattern via Spring's cache abstraction, in front of all four endpoints.
- **Observability**: Prometheus (metrics) + Loki (logs, shipped via Grafana Alloy) + Tempo (traces, via
  OTLP) + Grafana (single pane of glass, datasources provisioned with trace/log/metric correlation), plus
  Postgres and Redis Prometheus exporters so database- and cache-level behavior is visible alongside
  application metrics.
- **Load testing**: Grafana k6, one script per endpoint, results pushed to Prometheus so a load-test run
  is visible in the same Grafana instance as the application's own telemetry for that time window.
- **Local orchestration**: a single `docker-compose.yaml` brings up the entire stack (database, cache,
  application, observability, and an opt-in `load-test` profile for k6).

```
Client -> imdb-service (Spring Boot)
              |-- reads/caches --> Redis
              |-- queries --------> PostgreSQL (abanda/imdb-postgresql)
              |-- metrics --------> Prometheus <-- postgres-exporter, redis-exporter
              |-- logs (stdout) --> Grafana Alloy --> Loki
              |-- traces (OTLP) --> Tempo
                                        \
                                         --> Grafana (dashboards, correlated across all three)

k6 (one script per endpoint, run in isolation) --> imdb-service, results --> Prometheus
```

## 9. Key Design Decisions & Rationale

| Decision | Chosen approach | Alternatives considered | Rationale |
|---|---|---|---|
| Six Degrees algorithm | Bidirectional recursive CTE in Postgres (two capped `WITH RECURSIVE` queries, one from each person, meeting in the middle), plus Redis-cached results | (a) One-sided recursive CTE from a fixed Kevin-Bacon root; (b) precomputed single-source BFS from Kevin Bacon; (c) in-memory bidirectional BFS in the JVM; (d) Pruned Landmark Labeling (PLL); (e) dedicated graph database (Neo4j) | The requirement isn't fixed to Kevin Bacon - any two people can be queried - which rules out precomputing from one root. A naive one-sided traversal risks combinatorial blowup through high-degree "hub" actors; meeting in the middle halves the exponent and, combined with the max-7-degree cap (so each side only expands ~4 hops), keeps worst-case cost bounded without new infrastructure. PLL (Akiba et al., SIGMOD 2013) is the literature's actual state-of-the-art for this class of problem - hub-labeling with microsecond exact queries at hundreds-of-millions-of-edges scale - but it's a bespoke indexing engine, disproportionate to build for this exercise; documented as the answer if this had to serve real production query volume. Neo4j is the standard "just use a graph database" answer but adds a second datastore to operate and keep in sync for no benefit at this data size. |
| Result caching | Redis, cache-aside, keyed by unordered person pair, storing the **true shortest distance up to the absolute 7-degree cap** regardless of the caller's requested max | Cache per (pair, requested-max) combination | A distance of, say, 5 is a fact independent of whether the caller asked for `maxDegree=3` or `maxDegree=7`; caching the true distance once lets every future request for that pair reuse it and simply filter against its own bound, instead of fragmenting the cache by request parameter. |
| Top-rated ranking | IMDb-style weighted (Bayesian) rating: `WR = (v/(v+m))*R + (m/(v+m))*C` | Sort by raw `average_rating` | A raw-average sort lets a movie with 3 votes at 10/10 outrank one with 500,000 votes at 8.9. The weighted formula is IMDb's own published approach for exactly this reason, and demonstrates the same ranking-under-uncertainty thinking the Six Degrees decision does. |
| Title search | PostgreSQL `pg_trgm` fuzzy/similarity search over `primary_title`/`original_title`, GIN-indexed | Exact/prefix `ILIKE` match | Users don't reliably know a title's exact casing/wording; trigram similarity tolerates typos and partial matches while still being index-backed (not a sequential scan), which matters given `title_basics` has millions of rows. |
| API ID format | Public API uses IMDb-style `tt`/`nm` string IDs; translated to the internal integer PK at the repository boundary | Expose the internal integer IDs directly | The integer PK is an artifact of this specific Docker image's import script, not a stable public contract. Consumers of an "IMDb copycat" API expect IMDb's own ID format. |
| Caching layer | Redis (new dependency) | In-process cache (e.g. Caffeine) | The load-testing goal explicitly requires observing cache behavior (hit/miss ratio) as a first-class signal via a Redis Prometheus exporter, and a shared external cache is the realistic production shape if this API ever ran more than one instance. |
| Build tool | Maven | Gradle | Consistent with `votee` elsewhere in this monorepo. |
| Six Degrees scope | Generalized: `personA`/`personB` are both arbitrary query inputs | Literal brief wording: fixed target = Kevin Bacon | Once a bidirectional traversal is the implementation (required regardless, to bound hub-actor blowup - see the algorithm row above), accepting two arbitrary people costs nothing extra - a Kevin-Bacon-fixed version would just be this same endpoint with one side pre-filled. Generalizing is strictly more capable for the same engineering cost, so there's no reason to artificially narrow it back to the literal brief wording. |

## 10. Success Criteria

- All three functional requirements are satisfied against the full (untruncated) dataset via the four
  endpoints in §6.2.
- The Six Degrees endpoint correctly computes degrees of separation between arbitrary people (not just
  relative to Kevin Bacon), respects the caller's `maxDegree` bound, and returns within its k6-tested
  latency threshold even for high-degree "hub" actors.
- `docker-compose up` brings up the full stack (database, cache, application, full observability stack)
  with no manual steps beyond that one command (excluding the one-time 20-30 minute dataset import).
- Every endpoint has a Grafana-visible trace, and its k6 load-test run is visible in the same Grafana
  instance correlated against application/DB/cache metrics for that time window.
- Integration tests pass in CI against a lightweight fixture dataset (not the full 20GB image - see the
  low-level design's test plan for why).

## 11. Risks & Open Questions

- **Hub-actor blowup risk remains partially empirical**: the bidirectional-CTE mitigation is
  literature-grounded, but its actual worst-case latency on this specific dataset's most prolific actors
  (some have thousands of credits) is only proven by the k6 results, not by design alone. If load testing
  reveals the recursive CTE still misbehaves at the extreme tail, the documented fallback is the
  precomputed/in-memory BFS alternative from §9, promoted from "documented" to "implemented."
- **`title_principals` has no enforced foreign keys** (the loader's `add_references` step is not
  consistently applied per its own source comments), so orphaned `nconst`/`tconst` references are
  possible in principle; queries need to tolerate missing joins rather than assume referential integrity.
- **Open question**: whether the `co_star_edges` materialized view (low-level design §4) needs a
  scheduled refresh in a longer-lived deployment, or whether "refresh once after the one-time data import"
  is sufficient given there's no write path. Deferred - not required for this pass's success criteria.
- **Open question**: exact `minVotes` default for the weighted-rating formula on top-rated movies. Needs a
  quick data-driven look at the vote-count distribution once the dataset is loaded, rather than guessing a
  round number up front - flagged as an implementation-time task in the low-level design.

## 12. Out of Scope / Future Work

- `title_akas` (localized alternate titles) and `title_episode` (TV episode hierarchy) are loaded but not
  surfaced by any endpoint in this pass.
- Authentication/authorization of any kind.
- Promoting Pruned Landmark Labeling or a dedicated graph database from "documented alternative" to
  "implemented," should load testing prove the chosen bidirectional-CTE approach insufficient at real
  production query volume.
- Multi-instance/horizontal scaling of the application tier (the design is cache/observability-ready for
  it, but it isn't exercised here).
- Swagger/OpenAPI documentation UI: `springdoc-openapi`'s Initializr `versionRange` doesn't yet cover
  Spring Boot 4.1 (see low-level design §11). Revisit once springdoc ships 4.1 support.

## 13. References

- Data source: [`abanda/imdb-postgresql`](https://github.com/icemc/imdb-postgresql)
- Requirements: [`REQUIREMENTS.md`](REQUIREMENTS.md)
- Akiba, Iwata, Yoshida, "Fast Exact Shortest-Path Distance Queries on Large Networks by Pruned Landmark
  Labeling," SIGMOD 2013 - [arXiv:1304.4661](https://arxiv.org/abs/1304.4661)
- Goldberg et al., "Reach for A*: Efficient Point-to-Point Shortest Path Algorithms" -
  [Microsoft Research](https://www.microsoft.com/en-us/research/wp-content/uploads/2006/01/tr-2005-132.pdf)
- Root repository context: [`/README.md`](../../README.md)
