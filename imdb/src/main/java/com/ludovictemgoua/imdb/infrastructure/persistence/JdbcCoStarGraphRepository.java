package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCoStarGraphRepository implements CoStarGraphRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final int sideCap;
    private final int fanOutCap;
    private final int absoluteMaxDegree;

    public JdbcCoStarGraphRepository(
            NamedParameterJdbcTemplate jdbc,
            @Value("${six-degrees.side-cap}") int sideCap,
            @Value("${six-degrees.fan-out-cap}") int fanOutCap,
            @Value("${six-degrees.absolute-max-degree}") int absoluteMaxDegree,
            @Value("${six-degrees.query-timeout-seconds}") int queryTimeoutSeconds) {
        this.jdbc = jdbc;
        this.sideCap = sideCap;
        this.fanOutCap = fanOutCap;
        this.absoluteMaxDegree = absoluteMaxDegree;
        // Only this query gets a tight timeout - it's the one query in the whole app whose cost
        // depends on graph shape (hub actors) rather than a bounded index lookup. There's no
        // `spring.jdbc.template.query-timeout` property to set this declaratively (Boot 4.1 doesn't
        // have one), so it's set directly on the underlying JdbcTemplate instead.
        ((JdbcTemplate) jdbc.getJdbcOperations()).setQueryTimeout(queryTimeoutSeconds);
    }

    @Override
    public Optional<GraphPath> findShortestPath(int personA, int personB) {
        String sql = """
                WITH RECURSIVE forward(person, depth, path) AS (
                    SELECT :personA, 0, ARRAY[:personA]
                  UNION ALL
                    SELECT nbr.person_b, f.depth + 1, f.path || nbr.person_b
                    FROM forward f
                    CROSS JOIN LATERAL (
                        SELECT e.person_b
                        FROM co_star_edges e
                        WHERE e.person_a = f.person
                        ORDER BY e.person_b
                        LIMIT :fanOutCap
                    ) nbr
                    WHERE f.depth < :sideCap
                      AND NOT nbr.person_b = ANY(f.path)
                ),
                backward(person, depth, path) AS (
                    SELECT :personB, 0, ARRAY[:personB]
                  UNION ALL
                    SELECT nbr.person_b, b.depth + 1, b.path || nbr.person_b
                    FROM backward b
                    CROSS JOIN LATERAL (
                        SELECT e.person_b
                        FROM co_star_edges e
                        WHERE e.person_a = b.person
                        ORDER BY e.person_b
                        LIMIT :fanOutCap
                    ) nbr
                    WHERE b.depth < :sideCap
                      AND NOT nbr.person_b = ANY(b.path)
                )
                SELECT f.depth + b.depth AS degree, f.path AS forward_path, b.path AS backward_path
                FROM forward f
                JOIN backward b ON b.person = f.person
                WHERE f.depth + b.depth <= :absoluteMaxDegree
                ORDER BY degree ASC
                LIMIT 1
                """;
        var params = new MapSqlParameterSource()
                .addValue("personA", personA).addValue("personB", personB)
                .addValue("sideCap", sideCap).addValue("fanOutCap", fanOutCap)
                .addValue("absoluteMaxDegree", absoluteMaxDegree);

        return jdbc.query(sql, params, JdbcCoStarGraphRepository::mapRawMatch).stream()
                .findFirst()
                .map(PathStitcher::stitch);
    }

    private static RawMatch mapRawMatch(ResultSet rs, int rowNum) throws SQLException {
        return new RawMatch(rs.getInt("degree"),
                toIntList(rs.getArray("forward_path")), toIntList(rs.getArray("backward_path")));
    }

    private static List<Integer> toIntList(Array sqlArray) throws SQLException {
        return Arrays.asList((Integer[]) sqlArray.getArray());
    }

    // Package-private: the forward/backward split is an artifact of this one bidirectional-CTE
    // implementation, not a domain concept. PathStitcher (same package) collapses it into the clean
    // GraphPath the CoStarGraphRepository interface actually promises.
    record RawMatch(int degree, List<Integer> forwardPath, List<Integer> backwardPath) {
    }
}
