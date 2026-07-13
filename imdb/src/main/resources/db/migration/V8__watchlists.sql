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
