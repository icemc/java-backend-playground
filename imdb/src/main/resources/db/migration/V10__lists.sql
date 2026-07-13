CREATE TABLE lists (
    id         SERIAL PRIMARY KEY,
    user_id    INTEGER NOT NULL REFERENCES users (id),
    name       TEXT NOT NULL,
    visibility TEXT NOT NULL DEFAULT 'PRIVATE',
    version    INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMPTZ
);

CREATE TABLE list_items (
    list_id  INTEGER NOT NULL REFERENCES lists (id),
    title_id INTEGER NOT NULL REFERENCES title_basics (tconst),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ordering SERIAL,
    PRIMARY KEY (list_id, title_id)
);
