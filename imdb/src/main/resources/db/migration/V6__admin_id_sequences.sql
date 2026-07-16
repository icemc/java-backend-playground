-- Admin-created titles/people (admin CRUD) need ids that can never collide with the seeded, already-
-- densely-allocated tconst/nconst range from abanda/imdb-postgresql. Starting each sequence one past
-- the current max is done in a DO block, not a plain CREATE SEQUENCE START WITH literal, since the
-- actual max differs across environments (the full dataset here vs. the small fixture Testcontainers
-- and the e2e stack seed) - this migration must work correctly against all three.
DO $$
DECLARE
    next_title_id BIGINT;
    next_person_id BIGINT;
BEGIN
    SELECT COALESCE(max(tconst), 0) + 1 INTO next_title_id FROM title_basics;
    SELECT COALESCE(max(nconst), 0) + 1 INTO next_person_id FROM name_basics;

    EXECUTE format('CREATE SEQUENCE title_id_seq START WITH %s', next_title_id);
    EXECUTE format('CREATE SEQUENCE person_id_seq START WITH %s', next_person_id);
END $$;
