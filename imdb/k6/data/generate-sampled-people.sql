-- Run against the fully-seeded database to produce a realistic load-test sample: a mix of
-- ordinary actors and high-degree "hub" actors (LLD §8 - six-degrees.js needs both to exercise
-- realistic and worst-case fan-out through co_star_edges under load).
--
--   psql -h localhost -U imdb -d imdb -f generate-sampled-people.sql --csv -o sampled-people.csv

WITH credit_counts AS (
    SELECT nconst, count(*) AS credits
    FROM title_principals
    WHERE category IN ('actor', 'actress', 'self')
    GROUP BY nconst
),
hub_actors AS (
    SELECT nconst FROM credit_counts WHERE credits > 200 ORDER BY random() LIMIT 100
),
ordinary_actors AS (
    SELECT nconst FROM credit_counts WHERE credits BETWEEN 2 AND 50 ORDER BY random() LIMIT 400
)
SELECT 'nm' || lpad(nb.nconst::text, 7, '0') AS id, nb.primary_name AS name
FROM name_basics nb
JOIN (SELECT nconst FROM hub_actors UNION SELECT nconst FROM ordinary_actors) sampled
  ON sampled.nconst = nb.nconst;
