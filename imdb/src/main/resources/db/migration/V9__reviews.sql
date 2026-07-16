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
