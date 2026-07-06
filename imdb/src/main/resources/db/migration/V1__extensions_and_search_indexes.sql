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
