CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_title_basics_primary_title_trgm
    ON title_basics USING gin (primary_title gin_trgm_ops);

CREATE INDEX idx_title_basics_original_title_trgm
    ON title_basics USING gin (original_title gin_trgm_ops);

-- title_basics.genres is TEXT[] in our own V0 fallback schema (Testcontainers) but GENRE[] (a
-- custom enum array) on the real abanda/imdb-postgresql-seeded database - genres::text[] works as a
-- query-time cast against either, but Postgres won't accept a bare cast in an index expression since
-- it can't prove the underlying enum-array-to-text-array cast function is IMMUTABLE. Wrapping it in
-- our own SQL function marked IMMUTABLE (accepting anyarray, so it still works against either
-- schema) sidesteps that - discovered when the plain ::text[] version failed at migration time
-- against the real, freshly-imported dataset with "functions in index expression must be marked
-- IMMUTABLE". JdbcTitleRepository's genre filter query must use this same function, not a raw cast,
-- so the planner recognizes the expression as matching the index.
CREATE OR REPLACE FUNCTION genres_as_text(anyarray) RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$ SELECT $1::text[] $$;

CREATE INDEX idx_title_basics_genres
    ON title_basics USING gin (genres_as_text(genres));

CREATE INDEX idx_title_ratings_rank
    ON title_ratings (average_rating DESC, num_votes DESC);

CREATE INDEX idx_title_principals_nconst_acting
    ON title_principals (nconst)
    WHERE category IN ('actor', 'actress', 'self');

CREATE INDEX idx_name_basics_primary_name_trgm
    ON name_basics USING gin (primary_name gin_trgm_ops);

-- Discovered under k6 load testing: short/common search terms (e.g. "man", "war", "day" - the bulk
-- of realistic single-word title searches) make pg_trgm's `%` operator match an enormous fraction of
-- the table, since a 3-4 letter string has very few distinct trigrams. Raising
-- pg_trgm.similarity_threshold does NOT help - EXPLAIN ANALYZE showed the GIN bitmap index scan
-- returns the exact same huge candidate set (832K+ rows for "man") regardless of threshold, because
-- the threshold is only applied as a post-fetch recheck, not at the index scan itself. The actual
-- fix is gin_fuzzy_search_limit, which caps how many rows a GIN index scan will return for a
-- low-selectivity query term: took the "man" query from 39.6s / 176,300 buffer reads down to ~120ms
-- / ~3,000 buffer reads, and turned a 100%-failure k6 run into a 100%-pass, p95=4.45ms run.
-- Trade-off (accepted deliberately): `totalElements` in the search response becomes an
-- under-count for very common terms, since it's counting a sampled candidate set rather than the
-- true total (Postgres's own docs describe gin_fuzzy_search_limit results as "likely to be
-- incomplete" - expected and fine for a fuzzy-search results page, not fine for anything relying on
-- an exact count). Set at the database level so every pooled HikariCP connection picks it up
-- automatically, without a per-query SET statement or Java code change. Uses current_database()
-- rather than hardcoding "imdb" - Testcontainers' PostgreSQLContainer defaults to a database named
-- "test", and this migration runs there too.
DO $$
BEGIN
    EXECUTE format('ALTER DATABASE %I SET gin_fuzzy_search_limit = 5000', current_database());
END $$;
