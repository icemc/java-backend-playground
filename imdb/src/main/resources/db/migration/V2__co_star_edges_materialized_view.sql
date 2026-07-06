CREATE MATERIALIZED VIEW co_star_edges AS
SELECT DISTINCT p1.nconst AS person_a, p2.nconst AS person_b
FROM title_principals p1
JOIN title_principals p2
    ON p1.tconst = p2.tconst
   AND p1.nconst <> p2.nconst
WHERE p1.category IN ('actor', 'actress', 'self')
  AND p2.category IN ('actor', 'actress', 'self');

CREATE UNIQUE INDEX idx_co_star_edges_pk ON co_star_edges (person_a, person_b);
