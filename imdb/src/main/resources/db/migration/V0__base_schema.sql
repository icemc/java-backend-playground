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
