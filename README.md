# Java Backend Playground

A monorepo of independent Java and JVM backend projects — a structured, deliberate return to Java after a couple of years spent primarily in other stacks.

![Java](https://img.shields.io/badge/Java-21-orange)
![Status](https://img.shields.io/badge/status-active-brightgreen)

## Why this repo exists

I'm a senior software engineer who spent the last couple of years working mostly outside the Java ecosystem. Rather than easing back in with tutorials, I'm using this repo to rebuild depth the way I'd want a project to be built professionally: real services, real trade-offs, tests, and documentation — not throwaway scripts.

Each subproject targets a different corner of the JVM world on purpose, so the exercise stays honest rather than repeating the same comfortable slice of the language.

## What this covers

- **Backend services** — REST APIs, persistence, and testing built with common JVM frameworks
- **Bare-metal Java** — framework-free exercises touching concurrency, the memory model, and JVM internals directly
- **Cross-JVM exploration** — the same problems occasionally revisited in other JVM languages (e.g. Scala) to compare idioms and trade-offs

## Projects

### [imdb](imdb/) — production-shaped REST API over the real IMDb dataset

The most recent, and most complete, project in this repo. A Spring Boot 4.1 / Java 21 API over the full, untruncated [IMDb Non-Commercial Dataset](https://www.imdb.com/interfaces/) - millions of titles, tens of millions of cast/crew credits - not a sample or a toy CRUD example. Started as four read-only endpoints (fuzzy search, top-rated by genre, and a generalized "Six Degrees of Kevin Bacon" graph query), then grew into a 48-endpoint API with JWT auth, admin CRUD, and a full user-content layer (watchlists, reviews, custom lists).

- **A real algorithm, not a toy one**: Six Degrees is a genuine bidirectional BFS, hand-rolled in PL/pgSQL after a first, CTE-based version passed review and its own tests, then failed under real data - a silent wrong-answer bug, plus a hub-to-hub query that took 3+ minutes and spilled to disk. The fix: **29-44ms and a correct answer** on the exact pathological pair that broke the original.
- **Full observability, not a metrics endpoint**: structured JSON logs, Prometheus metrics, and OpenTelemetry distributed tracing, correlated in one Grafana instance - down to individual SQL statements and Redis commands as their own spans in a real trace waterfall, and every log line carrying the trace ID that produced it.
- **Load-tested against itself**: k6 scripts exercise every endpoint in isolation, then all 48 simultaneously; the combined run found and fixed five real bugs (HikariCP pool exhaustion, three schema drifts between the test and real database, and an id sequence colliding with orphaned imported rows) rather than just producing a green checkmark.
- **Interactive, fully-documented API** (Swagger UI / Redoc) - every endpoint carries a real summary, description, and per-status-code response doc, not auto-generated `delete_3`-style operationIds.
- **Three-tier CI pipeline**: unit → Testcontainers integration → Postman/Newman e2e against the real built Docker image, not a mocked slice.

<img src="imdb/assets/swagger-ui.png" alt="Swagger UI showing grouped, fully-documented imdb endpoints" width="800">

<img src="imdb/assets/6-degree-dashboard.png" alt="Grafana dashboard breaking down Six Degrees latency against the other three endpoints" width="800">

<img src="imdb/assets/tempo-datasource-grafana-queries.png" alt="A real distributed trace in Tempo, opened directly from Grafana" width="800">

Full README, with the complete architecture, all four dashboards, and a live trace shape: [imdb/README.md](imdb/README.md).

### [votee](votee/) — exact-arithmetic vote-counting library

A Java 21 port of [votee-scala](votee-scala/), an existing Scala 3 library of mine, implementing nine vote-counting algorithms (Majority, Super Majority, Approval, Veto, Borda Count, Baldwin, Contingent Vote, Coombs' Method, Exhaustive Ballot) behind one shared, generic `Election<C, B, W>` contract.

- **Correctness over convenience**: every vote weight and score is tracked as an exact `Rational` (`BigInteger` numerator/denominator, reduced to lowest terms), not a `double` - tallies never drift from floating-point rounding, no matter how many rounds an election runs.
- **Ported for behavioral parity, not just API shape**: every algorithm is checked against the same JSON fixtures the Scala reference's own test suite uses; every place this port deliberately diverges from that reference is called out and reasoned about individually, not silently different.
- **Genuinely extensible**: bring your own `Candidate`/`Ballot`/`Winner` types by implementing the library's contracts directly - every algorithm is generic over `<C extends Candidate, B extends Ballot<C, B>>`, so a custom domain type works with zero changes to the algorithm classes themselves.
- **46 tests**, published to a private GitHub Packages Maven registry under Early SemVer.

Full README, including the algorithm table, extension guide, and every documented deviation from the Scala reference: [votee/README.md](votee/README.md).

## How this repo is organized

This is a monorepo: every top-level directory is a self-contained project with its own build tooling, tests, and README. This root README intentionally stays high-level — open a project's folder for details on its stack, design decisions, and how to run it.

## Status

This repository is actively growing. Projects are added here once they reach a working, documented state, so what you see reflects what's actually done rather than a list of intentions.

## About the author

**Ludovic Temgoua Abanda** — Senior Software Engineer

- GitHub: [github.com/icemc](https://github.com/icemc)
- LinkedIn: [linkedin.com/in/ludovic-temgoua-abanda](https://www.linkedin.com/in/ludovic-temgoua-abanda/)
- Portfolio: [ludovictemgoua.com](https://www.ludovictemgoua.com)
