-- Both enums mirror what abanda/imdb-postgresql's own imdblib import actually creates in the real
-- dev database (title_basics.title_type, title_principals.category) - confirmed via
-- information_schema against the live container, not assumed. This migration only ever executes
-- against a genuinely empty schema (Testcontainers; the real dev DB is baselined at V0 and never
-- runs this file), so matching production here is what keeps the two environments' schemas from
-- silently drifting apart. Guarded with a DO block since CREATE TYPE has no IF NOT EXISTS clause.
DO $$ BEGIN
    CREATE TYPE title_type AS ENUM (
        'movie', 'short', 'tvEpisode', 'tvMiniSeries', 'tvMovie', 'tvPilot',
        'tvSeries', 'tvShort', 'tvSpecial', 'video', 'videoGame'
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE category AS ENUM (
        'actor', 'actress', 'self', 'writer', 'director', 'producer', 'editor',
        'cinematographer', 'composer', 'production_designer', 'casting_director',
        'archive_footage', 'archive_sound'
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

DO $$ BEGIN
    CREATE TYPE genre AS ENUM (
        'Action', 'Adult', 'Adventure', 'Animation', 'Biography', 'Comedy', 'Crime',
        'Documentary', 'Drama', 'Family', 'Fantasy', 'Film-Noir', 'Game-Show', 'History',
        'Horror', 'Music', 'Musical', 'Mystery', 'News', 'Reality-TV', 'Romance', 'Sci-Fi',
        'Short', 'Sport', 'Talk-Show', 'Thriller', 'War', 'Western'
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

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
    title_type title_type NOT NULL,
    primary_title TEXT NOT NULL,
    original_title TEXT NOT NULL,
    is_adult BOOLEAN NOT NULL DEFAULT FALSE,
    start_year INTEGER,
    end_year INTEGER,
    runtime_minutes INTEGER,
    genres genre[]
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
    category category NOT NULL,
    job TEXT,
    characters TEXT[],
    PRIMARY KEY (tconst, ordering)
);
