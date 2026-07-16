-- V6 seeded title_id_seq/person_id_seq from each id's own table only (max(tconst) FROM title_basics,
-- max(nconst) FROM name_basics) - discovered under k6 admin-write load testing that title_principals
-- (and, in principle, title_ratings/title_crew/name_basics.known_for_titles) can reference a tconst
-- higher than title_basics' own max: raw IMDb exports are dumped as a set of independently-snapshotted
-- files, so title.principals.tsv can reference a title that title.basics.tsv's own snapshot doesn't
-- have a row for (confirmed live: 6830 such orphaned title_principals rows on this dataset). Once
-- title_id_seq's nextval() reached one of those orphaned tconsts, POST /titles/{id}/principals started
-- failing with a title_principals_pkey duplicate-key error - a real, already-imported credit sitting at
-- the exact (tconst, ordering) an admin-created title was about to reuse. Recomputing the baseline as
-- the greatest tconst/nconst referenced ANYWHERE in the schema, not just each id's own table, closes
-- the gap. person_id_seq isn't currently colliding (admin person creation during this session's testing
-- already pushed it past every orphaned nconst), but has the identical latent exposure on a fresh
-- import, so it's corrected here too rather than waiting for its own load-test failure to prove it.
DO $$
DECLARE
    next_title_id BIGINT;
    next_person_id BIGINT;
BEGIN
    SELECT GREATEST(
        COALESCE((SELECT max(tconst) FROM title_basics), 0),
        COALESCE((SELECT max(tconst) FROM title_ratings), 0),
        COALESCE((SELECT max(tconst) FROM title_crew), 0),
        COALESCE((SELECT max(tconst) FROM title_principals), 0),
        COALESCE((SELECT max(t) FROM (SELECT unnest(known_for_titles) AS t FROM name_basics) k), 0)
    ) + 1 INTO next_title_id;

    SELECT GREATEST(
        COALESCE((SELECT max(nconst) FROM name_basics), 0),
        COALESCE((SELECT max(nconst) FROM title_principals), 0),
        COALESCE((SELECT max(n) FROM (SELECT unnest(directors) AS n FROM title_crew) d), 0),
        COALESCE((SELECT max(n) FROM (SELECT unnest(writers) AS n FROM title_crew) w), 0)
    ) + 1 INTO next_person_id;

    PERFORM setval('title_id_seq', next_title_id, false);
    PERFORM setval('person_id_seq', next_person_id, false);
END $$;
