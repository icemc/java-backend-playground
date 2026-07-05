# IMDb Copycat - Requirements

Part of a personal return to Java after a couple of years spent primarily in other stacks (see the
[root README](../../README.md)) - a deliberate exercise in building a production-grade REST API with
Spring Boot against a real, large, publicly available dataset rather than a toy example. The dataset is
IMDb's own [Non-Commercial Dataset](https://www.imdb.com/interfaces/), which offers rich, realistic
movie/TV/people data at real scale (millions of rows) - exactly the kind of data volume where "does the
indexing strategy actually hold up" and "does the algorithm choice actually scale" stop being academic
questions.

See [`product-design.md`](product-design.md) for the full design rationale and
[`low-level-design.md`](low-level-design.md) for schema, endpoint contracts, and implementation detail.

## Guidelines

- Treat this as a real production service, not a toy: project structure, code quality, readability,
  documentation, tests, CI.
- Java / Spring Boot for the API layer; the rest of the stack is chosen to fit the domain - see the design
  docs for what and why.
- Don't truncate the dataset. It's feasible to hold all of it on a single machine; working against the
  real data volume is the point of the exercise.

## The Requirements

### Requirement #1 (easy): Title search

Search by a movie's primary title or original title. The result should include related information,
including cast and crew.

### Requirement #2 (easy): Top-rated movies by genre

Given a genre, return the top-rated movies in that genre.

### Requirement #3 (difficult): Six Degrees of Kevin Bacon, generalized

[Six degrees of Kevin
Bacon](https://en.wikipedia.org/wiki/Six_Degrees_of_Kevin_Bacon): given two people (e.g. actors),
determine their degree of separation. Generalized to any two people rather than fixed to Kevin Bacon
specifically (see `product-design.md` §9 for why), bounded to a maximum of 7 degrees.

## Data Source & Setup

This implementation uses a personal Docker image, [`abanda/imdb-postgresql`](https://github.com/icemc/imdb-postgresql),
which loads the **full** IMDb Non-Commercial Dataset (including TV episodes) into PostgreSQL 17 across
seven tables (`name_basics`, `title_basics`, `title_ratings`, `title_crew`, `title_episode`,
`title_principals`, `title_akas`). Connection details:

```
JDBC URL = jdbc:postgresql://localhost:5432/imdb
Username = imdb
Password = password
```

IDs (`tconst`/`nconst`) are stored as plain integers in this schema (the `tt`/`nm` prefix and leading
zeros are stripped on import); the API translates between IMDb-style string IDs and these internal
integers at the boundary - see the low-level design for details.

### Running it

1. Run `docker-compose up` from the `imdb/` directory. This brings up PostgreSQL (seeded via
   `abanda/imdb-postgresql`), Redis, and the full observability stack (Prometheus, Loki, Tempo, Grafana,
   Grafana Alloy, plus Postgres/Redis exporters) - see `docker-compose.yaml` and the `observability/`
   directory. The first run takes 20-30 minutes while Postgres imports the dataset; `docker-compose logs -f postgres`
   shows import progress.
2. The Spring Boot application (`imdb-service`, Java 21 / Maven, scaffolded via
   [start.spring.io](https://start.spring.io/)) starts as part of the same `docker-compose up` once the
   project exists in this directory - see the low-level design for module layout and dependencies.
3. Grafana is available at `http://localhost:3000` (anonymous admin access, local-only) with Prometheus,
   Loki, and Tempo pre-provisioned as datasources with trace/log/metric correlation wired up.
4. Load tests live under `imdb/k6/`, one script per endpoint. They are not part of `docker-compose up` by
   default (`k6` is behind the `load-test` compose profile) and are run one at a time, e.g.:
   `docker-compose --profile load-test run k6 run /scripts/six-degrees.js`.

### Scope

Four read-only REST endpoints cover the three requirements above. No authentication, no write endpoints -
see the product design document's Non-Goals section for the full list of deliberate exclusions.
