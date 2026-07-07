-- Co-star chain: 1-2 (title 100), 2-3 (101), 3-4 (102), 4-5 (103), 5-6 (104) - a known 5-degree
-- separation between person 1 and person 6 that requires more than one hop on each side of the
-- bidirectional search (sideCap=4 per side, LLD §5.2). Person 7 is deliberately isolated (the only
-- credited principal on their one title) to exercise the "no path found" case.

INSERT INTO name_basics (nconst, primary_name, birth_year, known_for_titles) VALUES
  (1, 'Kevin Bacon', 1958, ARRAY[100]),
  (2, 'Tom Cruise', 1962, ARRAY[100, 101]),
  (3, 'Jack Nicholson', 1937, ARRAY[101, 102]),
  (4, 'Morgan Freeman', 1937, ARRAY[102, 103]),
  (5, 'Tim Robbins', 1958, ARRAY[103, 104]),
  (6, 'Tom Hanks', 1956, ARRAY[104]),
  (7, 'Isolated Actor', 1980, ARRAY[105]),
  (10, 'Rob Reiner', 1947, ARRAY[]::integer[]),
  (11, 'Aaron Sorkin', 1961, ARRAY[]::integer[]),
  (20, 'Jamie Lee', 1975, ARRAY[]::integer[]),
  (21, 'Jamie Lee', 1990, ARRAY[]::integer[]);

INSERT INTO title_basics (tconst, title_type, primary_title, original_title, start_year, genres) VALUES
  (100, 'movie', 'A Few Good Men', 'A Few Good Men', 1992, ARRAY['Drama']),
  (101, 'movie', 'Movie B', 'Movie B', 1995, ARRAY['Drama']),
  (102, 'movie', 'Movie C', 'Movie C', 1998, ARRAY['Drama']),
  (103, 'movie', 'Movie D', 'Movie D', 2001, ARRAY['Drama']),
  (104, 'movie', 'The Terminal', 'The Terminal', 2004, ARRAY['Drama']),
  (105, 'movie', 'Solo Film', 'Solo Film', 2010, ARRAY['Drama']),
  (200, 'movie', 'High Vote Solid Rating', 'High Vote Solid Rating', 2000, ARRAY['Action']),
  (201, 'movie', 'Low Vote Perfect Rating', 'Low Vote Perfect Rating', 2000, ARRAY['Action']),
  (202, 'movie', 'Average Movie A', 'Average Movie A', 2000, ARRAY['Action']),
  (203, 'movie', 'Average Movie B', 'Average Movie B', 2000, ARRAY['Action']);

-- The weighted-rating test case (queried with minVotes=100, PDD §9): 201's raw average (10.0) beats
-- 200's (8.9), and 201's vote count (100) exactly clears the minVotes floor - but against a realistic
-- pool mean (padded by 202/203 at 5.0), the Bayesian shrinkage pulls 201's weighted score down enough
-- that 200 - overwhelmingly supported by 500,000 votes - still ranks first. This is deliberately tuned
-- so the assertion actually exercises the formula: at minVotes = m, anything that just clears the
-- filter is shrunk exactly halfway to the pool mean, which only overturns a raw-rating gap this size
-- if the pool mean is pulled low enough by the other titles.
INSERT INTO title_ratings (tconst, average_rating, num_votes) VALUES
  (100, 8.0, 500000),
  (101, 7.5, 400000),
  (102, 7.0, 300000),
  (103, 7.8, 350000),
  (104, 7.7, 450000),
  (105, 6.0, 10),
  (200, 8.9, 500000),
  (201, 10.0, 100),
  (202, 5.0, 200000),
  (203, 5.0, 200000);

INSERT INTO title_crew (tconst, directors, writers) VALUES
  (100, ARRAY[10], ARRAY[11]);

INSERT INTO title_principals (tconst, ordering, nconst, category) VALUES
  (100, 1, 1, 'actor'),
  (100, 2, 2, 'actor'),
  (101, 1, 2, 'actor'),
  (101, 2, 3, 'actor'),
  (102, 1, 3, 'actor'),
  (102, 2, 4, 'actor'),
  (103, 1, 4, 'actor'),
  (103, 2, 5, 'actor'),
  (104, 1, 5, 'actor'),
  (104, 2, 6, 'actor'),
  (105, 1, 7, 'actor');

-- co_star_edges is a materialized view (LLD §3.3) - it does not auto-update when title_principals
-- changes underneath it, so every fixture load must explicitly refresh it.
REFRESH MATERIALIZED VIEW co_star_edges;
