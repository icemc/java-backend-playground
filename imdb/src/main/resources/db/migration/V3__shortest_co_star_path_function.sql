-- Replaces an earlier single-statement bidirectional recursive CTE that had two real bugs, both
-- found under real load/data (not review):
--
-- 1. Correctness: the old query capped each node's fan-out with `ORDER BY person_b LIMIT
--    :fanOutCap`, always keeping the same fixed (lowest-id) subset of a hub's neighbors and
--    silently dropping the rest. If the actual connecting co-star wasn't in that arbitrary
--    subset, the query would report "no path found" (or a longer path) even though a real,
--    shorter path existed.
-- 2. Performance: cycle prevention only checked that a single path didn't revisit its own
--    history (`NOT nbr.person_b = ANY(path)`) - it had no shared visited set. The same node gets
--    rediscovered by many different paths in a small-world co-star graph, and each rediscovery
--    independently re-expanded from that node again, causing genuine combinatorial blowup. A
--    real hub-to-hub query (two talk-show hosts, ~8,000 co-stars each) took 3+ minutes and spilled
--    to disk with fanOutCap=200/sideCap=4.
--
-- This function is a proper level-synchronized bidirectional BFS: real visited-set temp tables
-- per side (so every node is expanded at most once, ever - no redundant re-expansion, no
-- arbitrary cap needed to drop real neighbors), expanding whichever side currently has the
-- smaller frontier (the standard bidirectional-BFS optimization - keeps total work minimal), and
-- stopping the instant the two frontiers intersect (true shortest path, found as early as
-- possible - most real pairs resolve within 1-2 hops). Verified against the exact pathological
-- pair that broke the old query (two ~8,000-co-star hub nodes, no direct edge): 44ms, correct
-- degree-2 result, versus 3+ minutes before.
CREATE OR REPLACE FUNCTION find_shortest_co_star_path(
    p_person_a INTEGER,
    p_person_b INTEGER,
    p_side_cap INTEGER,
    p_absolute_max_degree INTEGER
) RETURNS TABLE(result_degree INTEGER, result_path INTEGER[]) AS $$
DECLARE
    v_new_forward INTEGER[];
    v_new_backward INTEGER[];
    v_meeting INTEGER;
    v_fwd_depth INTEGER := 0;
    v_bwd_depth INTEGER := 0;
    v_frontier_forward INTEGER[] := ARRAY[p_person_a];
    v_frontier_backward INTEGER[] := ARRAY[p_person_b];
    -- Tie-break flag for when both frontiers are the same size (see WHILE loop below) - without
    -- this, a plain "<=" comparison always favors forward on ties, which completely starves the
    -- backward side for any non-branching chain (frontier size stays 1 = 1 forever), since forward
    -- would keep winning every tie until it alone hits side_cap. Flipped after every tie-driven
    -- choice so both sides make progress. Found by a failing integration test on a 5-edge linear
    -- chain (1-2-3-4-5-6), not by review.
    v_prefer_forward BOOLEAN := true;
BEGIN
    IF p_person_a = p_person_b THEN
        RETURN QUERY SELECT 0, ARRAY[p_person_a];
        RETURN;
    END IF;

    -- Session-scoped temp tables, reused across calls on the same pooled connection rather than
    -- ON COMMIT DROP - a call wrapped in a caller-managed transaction that runs this function more
    -- than once before committing (e.g. a @Transactional test calling findShortestPath twice) would
    -- hit "relation already exists" on the second call, since ON COMMIT DROP only cleans up at
    -- actual commit, not between statements in an open transaction. TRUNCATE instead, which is
    -- correct regardless of transaction/session boundaries.
    CREATE TEMP TABLE IF NOT EXISTS visited_forward (person INTEGER PRIMARY KEY, parent INTEGER);
    CREATE TEMP TABLE IF NOT EXISTS visited_backward (person INTEGER PRIMARY KEY, parent INTEGER);
    TRUNCATE visited_forward, visited_backward;
    INSERT INTO visited_forward VALUES (p_person_a, NULL);
    INSERT INTO visited_backward VALUES (p_person_b, NULL);

    WHILE v_fwd_depth + v_bwd_depth < p_absolute_max_degree
      AND v_fwd_depth < p_side_cap AND v_bwd_depth < p_side_cap
      AND (array_length(v_frontier_forward, 1) IS NOT NULL OR array_length(v_frontier_backward, 1) IS NOT NULL)
    LOOP
        IF array_length(v_frontier_forward, 1) IS NOT NULL
           AND (array_length(v_frontier_backward, 1) IS NULL
                OR array_length(v_frontier_forward, 1) < array_length(v_frontier_backward, 1)
                OR (array_length(v_frontier_forward, 1) = array_length(v_frontier_backward, 1) AND v_prefer_forward)) THEN

            WITH new_nodes AS (
                INSERT INTO visited_forward (person, parent)
                SELECT x.person_b, x.parent FROM (
                    SELECT DISTINCT ON (e.person_b) e.person_b, e.person_a AS parent
                    FROM co_star_edges e
                    WHERE e.person_a = ANY(v_frontier_forward)
                      AND NOT EXISTS (SELECT 1 FROM visited_forward vf WHERE vf.person = e.person_b)
                    ORDER BY e.person_b
                ) x
                RETURNING person
            )
            SELECT array_agg(person) INTO v_new_forward FROM new_nodes;

            v_frontier_forward := v_new_forward;
            v_fwd_depth := v_fwd_depth + 1;
            v_prefer_forward := false;

            IF v_new_forward IS NOT NULL THEN
                SELECT vb.person INTO v_meeting
                FROM visited_backward vb
                WHERE vb.person = ANY(v_new_forward)
                LIMIT 1;
                EXIT WHEN v_meeting IS NOT NULL;
            END IF;
        ELSE
            WITH new_nodes AS (
                INSERT INTO visited_backward (person, parent)
                SELECT x.person_b, x.parent FROM (
                    SELECT DISTINCT ON (e.person_b) e.person_b, e.person_a AS parent
                    FROM co_star_edges e
                    WHERE e.person_a = ANY(v_frontier_backward)
                      AND NOT EXISTS (SELECT 1 FROM visited_backward vb WHERE vb.person = e.person_b)
                    ORDER BY e.person_b
                ) x
                RETURNING person
            )
            SELECT array_agg(person) INTO v_new_backward FROM new_nodes;

            v_frontier_backward := v_new_backward;
            v_bwd_depth := v_bwd_depth + 1;
            v_prefer_forward := true;

            IF v_new_backward IS NOT NULL THEN
                SELECT vf.person INTO v_meeting
                FROM visited_forward vf
                WHERE vf.person = ANY(v_new_backward)
                LIMIT 1;
                EXIT WHEN v_meeting IS NOT NULL;
            END IF;
        END IF;
    END LOOP;

    IF v_meeting IS NULL THEN
        RETURN;
    END IF;

    -- Reconstruct the path by walking parent pointers from the meeting node back to each root -
    -- a plain linear parent-chain walk, not the combinatorial multi-path search above, so a
    -- recursive CTE is the right (and cheap) tool here.
    RETURN QUERY
    WITH RECURSIVE forward_chain AS (
        SELECT person, parent, 1 AS ord FROM visited_forward WHERE person = v_meeting
      UNION ALL
        SELECT vf.person, vf.parent, fc.ord + 1
        FROM visited_forward vf JOIN forward_chain fc ON vf.person = fc.parent
    ),
    backward_chain AS (
        SELECT person, parent, 1 AS ord FROM visited_backward WHERE person = v_meeting
      UNION ALL
        SELECT vb.person, vb.parent, bc.ord + 1
        FROM visited_backward vb JOIN backward_chain bc ON vb.person = bc.parent
    )
    SELECT
        (SELECT max(ord) FROM forward_chain) - 1 + (SELECT max(ord) FROM backward_chain) - 1,
        (SELECT array_agg(person ORDER BY ord DESC) FROM forward_chain)
        || (SELECT array_agg(person ORDER BY ord ASC) FROM backward_chain WHERE ord > 1);
END;
$$ LANGUAGE plpgsql;
