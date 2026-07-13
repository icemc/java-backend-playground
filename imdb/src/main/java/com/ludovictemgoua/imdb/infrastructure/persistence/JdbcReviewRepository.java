package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.RatingAggregate;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcReviewRepository implements ReviewRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcReviewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Review insert(int userId, int titleId, int rating, String body) {
        String sql = """
                INSERT INTO reviews (user_id, title_id, rating, body) VALUES (:userId, :titleId, :rating, :body)
                """;
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("titleId", titleId)
                .addValue("rating", rating).addValue("body", body);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        return findByUserAndTitle(userId, titleId).orElseThrow();
    }

    @Override
    public Optional<Review> findByUserAndTitle(int userId, int titleId) {
        String sql = """
                SELECT * FROM reviews WHERE user_id = :userId AND title_id = :titleId AND deleted_at IS NULL
                """;
        return jdbc.query(sql, Map.of("userId", userId, "titleId", titleId), JdbcReviewRepository::mapReview)
                .stream().findFirst();
    }

    @Override
    public WriteResult update(int reviewId, int rating, String body, int expectedVersion) {
        String sql = """
                UPDATE reviews SET rating = :rating, body = :body, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("rating", rating).addValue("body", body)
                .addValue("id", reviewId).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDelete(int reviewId, int expectedVersion) {
        String sql = """
                UPDATE reviews SET deleted_at = now() WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = Map.of("id", reviewId, "expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public PagedResult<Review> findByTitle(int titleId, int page, int size) {
        String dataSql = """
                SELECT * FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("titleId", titleId).addValue("limit", size).addValue("offset", (long) page * size);
        List<Review> content = jdbc.query(dataSql, params, JdbcReviewRepository::mapReview);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public PagedResult<Review> findByUser(int userId, int page, int size) {
        String dataSql = """
                SELECT * FROM reviews WHERE user_id = :userId AND deleted_at IS NULL
                ORDER BY created_at DESC LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM reviews WHERE user_id = :userId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", size).addValue("offset", (long) page * size);
        List<Review> content = jdbc.query(dataSql, params, JdbcReviewRepository::mapReview);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public RatingAggregate aggregateForTitle(int titleId) {
        String sql = """
                SELECT COALESCE(AVG(rating), 0) AS avg_rating, COUNT(*) AS review_count
                FROM reviews WHERE title_id = :titleId AND deleted_at IS NULL
                """;
        return jdbc.queryForObject(sql, Map.of("titleId", titleId), (rs, rowNum) ->
                new RatingAggregate(rs.getDouble("avg_rating"), rs.getInt("review_count")));
    }

    private static Review mapReview(ResultSet rs, int rowNum) throws SQLException {
        return new Review(rs.getInt("id"), rs.getInt("user_id"), rs.getInt("title_id"), rs.getInt("rating"),
                rs.getString("body"), rs.getInt("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
