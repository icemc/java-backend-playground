package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.User;
import com.ludovictemgoua.imdb.domain.repository.UserRepository;
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
public class JdbcUserRepository implements UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User insert(String email, String passwordHash, String displayName, Role role) {
        String sql = """
                INSERT INTO users (email, password_hash, display_name, role)
                VALUES (:email, :passwordHash, :displayName, :role)
                """;
        var params = new MapSqlParameterSource()
                .addValue("email", email).addValue("passwordHash", passwordHash)
                .addValue("displayName", displayName).addValue("role", role.name());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        int id = keyHolder.getKey().intValue();
        return new User(id, email, passwordHash, displayName, null, role, 0);
    }

    @Override
    public Optional<User> findById(int id) {
        String sql = "SELECT * FROM users WHERE id = :id AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("id", id), JdbcUserRepository::mapUser).stream().findFirst();
    }

    @Override
    public Optional<User> findByEmail(String email) {
        String sql = "SELECT * FROM users WHERE email = :email AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("email", email), JdbcUserRepository::mapUser).stream().findFirst();
    }

    @Override
    public boolean existsByEmail(String email) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = :email AND deleted_at IS NULL",
                Map.of("email", email), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public WriteResult updateProfile(int id, String displayName, String bio, int expectedVersion) {
        if (findById(id).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                UPDATE users SET display_name = :displayName, bio = :bio, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("displayName", displayName).addValue("bio", bio)
                .addValue("id", id).addValue("expectedVersion", expectedVersion);
        int updated = jdbc.update(sql, params);
        return updated == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public void updateRole(int id, Role role) {
        jdbc.update("UPDATE users SET role = :role, version = version + 1 WHERE id = :id",
                new MapSqlParameterSource().addValue("role", role.name()).addValue("id", id));
    }

    @Override
    public void softDelete(int id) {
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = :id", Map.of("id", id));
    }

    @Override
    public PagedResult<User> findAll(int page, int size) {
        String dataSql = "SELECT * FROM users WHERE deleted_at IS NULL ORDER BY id LIMIT :limit OFFSET :offset";
        String countSql = "SELECT count(*) FROM users WHERE deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        List<User> content = jdbc.query(dataSql, params, JdbcUserRepository::mapUser);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    private static User mapUser(ResultSet rs, int rowNum) throws SQLException {
        return new User(rs.getInt("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("bio"),
                Role.valueOf(rs.getString("role")), rs.getInt("version"));
    }
}
