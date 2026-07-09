package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
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
    private final int absoluteMaxDegree;

    public JdbcCoStarGraphRepository(
            DataSource dataSource,
            @Value("${six-degrees.side-cap}") int sideCap,
            @Value("${six-degrees.absolute-max-degree}") int absoluteMaxDegree,
            @Value("${six-degrees.query-timeout-seconds}") int queryTimeoutSeconds) {
        // A dedicated JdbcTemplate over the same DataSource/connection pool, not the app's shared
        // auto-configured NamedParameterJdbcTemplate bean - setQueryTimeout mutates the JdbcTemplate
        // instance itself, and Spring only creates one shared instance of that bean by default, so
        // setting the timeout there was leaking this query's tight timeout onto every other
        // repository's queries (discovered when a plain title search got cancelled by this six-degrees-
        // specific timeout). Only this query gets a tight timeout - it's the one query in the whole app
        // whose cost depends on graph shape (hub actors) rather than a bounded index lookup. There's no
        // `spring.jdbc.template.query-timeout` property to set this declaratively (Boot 4.1 doesn't have
        // one), so it's set directly on this repository's own JdbcTemplate instead.
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(queryTimeoutSeconds);
        this.jdbc = new NamedParameterJdbcTemplate(template);
        this.sideCap = sideCap;
        this.absoluteMaxDegree = absoluteMaxDegree;
    }

    @Override
    public Optional<GraphPath> findShortestPath(int personA, int personB) {
        // The actual bidirectional-BFS logic lives entirely in the find_shortest_co_star_path SQL
        // function (V3 migration) - a real level-synchronized BFS with a genuine per-side visited
        // set, replacing an earlier single-statement recursive CTE that had no visited set (only
        // per-path cycle checks) and capped fan-out with an arbitrary ORDER BY that could silently
        // drop the true shortest path. See the V3 migration for the full rationale.
        String sql = "SELECT * FROM find_shortest_co_star_path(:personA, :personB, :sideCap, :absoluteMaxDegree)";
        var params = new MapSqlParameterSource()
                .addValue("personA", personA).addValue("personB", personB)
                .addValue("sideCap", sideCap).addValue("absoluteMaxDegree", absoluteMaxDegree);

        return jdbc.query(sql, params, JdbcCoStarGraphRepository::mapGraphPath).stream().findFirst();
    }

    private static GraphPath mapGraphPath(ResultSet rs, int rowNum) throws SQLException {
        return new GraphPath(rs.getInt("result_degree"), toIntList(rs.getArray("result_path")));
    }

    private static List<Integer> toIntList(Array sqlArray) throws SQLException {
        return Arrays.asList((Integer[]) sqlArray.getArray());
    }
}
