package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistItemView;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcWatchlistRepository implements WatchlistRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcWatchlistRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WatchlistView findOrCreateByUserId(int userId) {
        return findByUserId(userId).orElseGet(() -> create(userId));
    }

    @Override
    public Optional<WatchlistView> findByUserId(int userId) {
        String sql = "SELECT id, user_id, visibility, version FROM watchlists WHERE user_id = :userId AND deleted_at IS NULL";
        return jdbc.query(sql, Map.of("userId", userId), (rs, rowNum) -> new int[]{rs.getInt("id")})
                .stream().findFirst()
                .map(row -> hydrate(row[0], userId));
    }

    @Override
    public WriteResult addItem(int watchlistId, int titleId) {
        String sql = """
                INSERT INTO watchlist_items (watchlist_id, title_id) VALUES (:watchlistId, :titleId)
                ON CONFLICT DO NOTHING
                """;
        jdbc.update(sql, Map.of("watchlistId", watchlistId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult removeItem(int watchlistId, int titleId) {
        jdbc.update("DELETE FROM watchlist_items WHERE watchlist_id = :watchlistId AND title_id = :titleId",
                Map.of("watchlistId", watchlistId, "titleId", titleId));
        return WriteResult.SUCCESS;
    }

    @Override
    public WriteResult updateVisibility(int watchlistId, Visibility visibility, int expectedVersion) {
        String sql = """
                UPDATE watchlists SET visibility = :visibility, version = version + 1
                WHERE id = :id AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("visibility", visibility.name()).addValue("id", watchlistId)
                .addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    private WatchlistView create(int userId) {
        String sql = "INSERT INTO watchlists (user_id) VALUES (:userId)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, new MapSqlParameterSource("userId", userId), keyHolder, new String[]{"id"});
        return new WatchlistView(keyHolder.getKey().intValue(), userId, Visibility.PRIVATE, 0, List.of());
    }

    private WatchlistView hydrate(int watchlistId, int userId) {
        String metaSql = "SELECT visibility, version FROM watchlists WHERE id = :id";
        var meta = jdbc.queryForMap(metaSql, Map.of("id", watchlistId));
        String itemsSql = """
                SELECT tb.tconst, tb.primary_title, wi.added_at
                FROM watchlist_items wi JOIN title_basics tb ON tb.tconst = wi.title_id
                WHERE wi.watchlist_id = :watchlistId AND tb.deleted_at IS NULL
                ORDER BY wi.added_at
                """;
        List<WatchlistItemView> items = jdbc.query(itemsSql, Map.of("watchlistId", watchlistId),
                (rs, rowNum) -> new WatchlistItemView(ImdbIds.formatTitleId(rs.getInt("tconst")),
                        rs.getString("primary_title"), rs.getTimestamp("added_at").toInstant()));
        return new WatchlistView(watchlistId, userId, Visibility.valueOf((String) meta.get("visibility")),
                ((Number) meta.get("version")).intValue(), items);
    }
}
