package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.ListItemView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
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
public class JdbcCustomListRepository implements CustomListRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCustomListRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CustomList insert(int userId, String name, Visibility visibility) {
        String sql = "INSERT INTO lists (user_id, name, visibility) VALUES (:userId, :name, :visibility)";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("name", name).addValue("visibility", visibility.name());
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, params, keyHolder, new String[]{"id"});
        return new CustomList(keyHolder.getKey().intValue(), userId, name, visibility, 0);
    }

    @Override
    public Optional<CustomListView> findById(int listId) {
        return hydrate(listId);
    }

    @Override
    public WriteResult update(int listId, String name, Visibility visibility, int expectedVersion) {
        String sql = """
                UPDATE lists SET name = :name, visibility = :visibility, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("name", name).addValue("visibility", visibility.name())
                .addValue("id", listId).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDelete(int listId, int expectedVersion) {
        String sql = "UPDATE lists SET deleted_at = now() WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL";
        var params = Map.of("id", listId, "expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public PagedResult<CustomList> findByUser(int userId, int page, int size) {
        String dataSql = """
                SELECT * FROM lists WHERE user_id = :userId AND deleted_at IS NULL
                ORDER BY id LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM lists WHERE user_id = :userId AND deleted_at IS NULL";
        var params = new MapSqlParameterSource()
                .addValue("userId", userId).addValue("limit", size).addValue("offset", (long) page * size);
        List<CustomList> content = jdbc.query(dataSql, params, JdbcCustomListRepository::mapList);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public PagedResult<CustomList> findPublic(int page, int size) {
        String dataSql = """
                SELECT * FROM lists WHERE visibility = 'PUBLIC' AND deleted_at IS NULL
                ORDER BY id LIMIT :limit OFFSET :offset
                """;
        String countSql = "SELECT count(*) FROM lists WHERE visibility = 'PUBLIC' AND deleted_at IS NULL";
        var params = new MapSqlParameterSource().addValue("limit", size).addValue("offset", (long) page * size);
        List<CustomList> content = jdbc.query(dataSql, params, JdbcCustomListRepository::mapList);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public WriteResult addItem(int listId, int titleId) {
        String sql = "INSERT INTO list_items (list_id, title_id) VALUES (:listId, :titleId) ON CONFLICT DO NOTHING";
        jdbc.update(sql, Map.of("listId", listId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult removeItem(int listId, int titleId) {
        jdbc.update("DELETE FROM list_items WHERE list_id = :listId AND title_id = :titleId",
                Map.of("listId", listId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    private Optional<CustomListView> hydrate(int listId) {
        String metaSql = "SELECT * FROM lists WHERE id = :id AND deleted_at IS NULL";
        List<CustomList> meta = jdbc.query(metaSql, Map.of("id", listId), JdbcCustomListRepository::mapList);
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        String itemsSql = """
                SELECT tb.tconst, tb.primary_title, li.added_at
                FROM list_items li JOIN title_basics tb ON tb.tconst = li.title_id
                WHERE li.list_id = :listId AND tb.deleted_at IS NULL
                ORDER BY li.ordering
                """;
        List<ListItemView> items = jdbc.query(itemsSql, Map.of("listId", listId),
                (rs, rowNum) -> new ListItemView(ImdbIds.formatTitleId(rs.getInt("tconst")),
                        rs.getString("primary_title"), rs.getTimestamp("added_at").toInstant()));
        CustomList list = meta.get(0);
        return Optional.of(new CustomListView(list.id(), list.userId(), list.name(), list.visibility(),
                list.version(), items));
    }

    private static CustomList mapList(ResultSet rs, int rowNum) throws SQLException {
        return new CustomList(rs.getInt("id"), rs.getInt("user_id"), rs.getString("name"),
                Visibility.valueOf(rs.getString("visibility")), rs.getInt("version"));
    }
}
