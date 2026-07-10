package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.CastMember;
import com.ludovictemgoua.imdb.domain.model.CreditedPerson;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.SharedTitle;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class JdbcTitleRepository implements TitleRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTitleRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcTitleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PagedResult<TitleSummary> search(String query, int page, int size) {
        String dataSql = """
                SELECT tconst, primary_title, original_title, title_type, start_year, end_year
                FROM title_basics
                WHERE primary_title % :query OR original_title % :query
                ORDER BY similarity(primary_title, :query) DESC
                LIMIT :limit OFFSET :offset
                """;
        String countSql = """
                SELECT count(*) FROM title_basics
                WHERE primary_title % :query OR original_title % :query
                """;
        var params = new MapSqlParameterSource()
                .addValue("query", query)
                .addValue("limit", size)
                .addValue("offset", (long) page * size);

        List<TitleSummary> content = jdbc.query(dataSql, params, JdbcTitleRepository::mapSummary);
        Long total = jdbc.queryForObject(countSql, params, Long.class);
        // total is an under-count for very common query terms (gin_fuzzy_search_limit, V1 migration)
        // - worth having at DEBUG to notice if that trade-off ever looks wrong for a specific term.
        log.debug("title search: query={} page={} size={} returned={} total={}",
                query, page, size, content.size(), total);
        return new PagedResult<>(content, total == null ? 0 : total, page, size);
    }

    @Override
    public Optional<TitleCore> findCore(int tconst) {
        String sql = """
                SELECT tb.tconst, tb.primary_title, tb.original_title, tb.title_type,
                       tb.start_year, tb.end_year, tb.runtime_minutes, tb.genres,
                       tr.average_rating, tr.num_votes
                FROM title_basics tb
                LEFT JOIN title_ratings tr ON tr.tconst = tb.tconst
                WHERE tb.tconst = :tconst
                """;
        return jdbc.query(sql, Map.of("tconst", tconst), JdbcTitleRepository::mapCore).stream().findFirst();
    }

    @Override
    public List<CreditedPerson> findDirectors(int tconst) {
        return findCrew(tconst, "directors");
    }

    @Override
    public List<CreditedPerson> findWriters(int tconst) {
        return findCrew(tconst, "writers");
    }

    @Override
    public List<CastMember> findTopCast(int tconst, int limit) {
        String sql = """
                SELECT tp.nconst, nb.primary_name, tp.category, tp.characters, tp.ordering
                FROM title_principals tp
                JOIN name_basics nb ON nb.nconst = tp.nconst
                WHERE tp.tconst = :tconst
                ORDER BY tp.ordering
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource().addValue("tconst", tconst).addValue("limit", limit);
        return jdbc.query(sql, params, JdbcTitleRepository::mapCastMember);
    }

    @Override
    public int countCast(int tconst) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM title_principals WHERE tconst = :tconst",
                Map.of("tconst", tconst), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<GenreTopRatedItem> findTopRated(String genre, int limit, int minVotes) {
        String sql = """
                WITH pool AS (
                    SELECT tb.tconst, tb.primary_title, tb.start_year, tr.average_rating, tr.num_votes
                    FROM title_basics tb
                    JOIN title_ratings tr ON tr.tconst = tb.tconst
                    WHERE tb.title_type = 'movie'
                      AND genres_as_text(tb.genres) @> ARRAY[:genre]::text[]
                      AND tr.num_votes >= :minVotes
                ),
                stats AS (SELECT AVG(average_rating) AS mean_rating FROM pool)
                SELECT p.tconst, p.primary_title, p.start_year, p.average_rating, p.num_votes,
                       (p.num_votes::numeric / (p.num_votes + :minVotes)) * p.average_rating
                       + (:minVotes::numeric / (p.num_votes + :minVotes)) * s.mean_rating AS weighted_rating
                FROM pool p CROSS JOIN stats s
                ORDER BY weighted_rating DESC
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource()
                .addValue("genre", genre).addValue("minVotes", minVotes).addValue("limit", limit);
        List<GenreTopRatedItem> results = jdbc.query(sql, params, JdbcTitleRepository::mapTopRated);
        log.debug("top rated: genre={} limit={} minVotes={} returned={}", genre, limit, minVotes, results.size());
        return results;
    }

    @Override
    public Optional<SharedTitle> findAnyCommonTitle(int personA, int personB) {
        String sql = """
                SELECT tb.tconst, tb.primary_title
                FROM title_principals p1
                JOIN title_principals p2 ON p1.tconst = p2.tconst
                JOIN title_basics tb ON tb.tconst = p1.tconst
                WHERE p1.nconst = :personA AND p2.nconst = :personB
                LIMIT 1
                """;
        var params = new MapSqlParameterSource().addValue("personA", personA).addValue("personB", personB);
        // findAnyCommonTitle was the site of a real bug (V4 migration) - title_principals had no
        // usable index on nconst, so this fell back to a 1s+ sequential scan of a 100M-row table on
        // every call. DEBUG timing here would have caught a regression of that fix immediately.
        long startMillis = System.currentTimeMillis();
        Optional<SharedTitle> result = jdbc.query(sql, params, (rs, rowNum) ->
                new SharedTitle(ImdbIds.formatTitleId(rs.getInt("tconst")), rs.getString("primary_title")))
                .stream().findFirst();
        log.debug("find any common title: personA={} personB={} durationMs={} found={}",
                personA, personB, System.currentTimeMillis() - startMillis, result.isPresent());
        return result;
    }

    private List<CreditedPerson> findCrew(int tconst, String column) {
        // column is only ever "directors" or "writers" below - both fixed internal literals, never
        // user input - so string-formatting it into the SQL here isn't an injection risk. Bind
        // parameters can't stand in for column/identifier names, only values.
        String sql = """
                SELECT nb.nconst, nb.primary_name
                FROM title_crew tc
                CROSS JOIN LATERAL unnest(tc.%s) AS crew(nconst)
                JOIN name_basics nb ON nb.nconst = crew.nconst
                WHERE tc.tconst = :tconst
                """.formatted(column);
        return jdbc.query(sql, Map.of("tconst", tconst),
                (rs, rowNum) -> new CreditedPerson(
                        ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name")));
    }

    private static TitleSummary mapSummary(ResultSet rs, int rowNum) throws SQLException {
        return new TitleSummary(
                ImdbIds.formatTitleId(rs.getInt("tconst")),
                rs.getString("primary_title"), rs.getString("original_title"), rs.getString("title_type"),
                (Integer) rs.getObject("start_year"), (Integer) rs.getObject("end_year"));
    }

    private static TitleCore mapCore(ResultSet rs, int rowNum) throws SQLException {
        List<String> genres = toStringList(rs.getArray("genres"));
        var avgRating = rs.getBigDecimal("average_rating");
        return new TitleCore(
                ImdbIds.formatTitleId(rs.getInt("tconst")),
                rs.getString("primary_title"), rs.getString("original_title"), rs.getString("title_type"),
                (Integer) rs.getObject("start_year"), (Integer) rs.getObject("end_year"),
                (Integer) rs.getObject("runtime_minutes"), genres,
                avgRating == null ? null : avgRating.doubleValue(),
                (Integer) rs.getObject("num_votes"));
    }

    private static CastMember mapCastMember(ResultSet rs, int rowNum) throws SQLException {
        return new CastMember(
                ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                rs.getString("category"), toStringList(rs.getArray("characters")), rs.getInt("ordering"));
    }

    private static GenreTopRatedItem mapTopRated(ResultSet rs, int rowNum) throws SQLException {
        return new GenreTopRatedItem(
                ImdbIds.formatTitleId(rs.getInt("tconst")), rs.getString("primary_title"),
                (Integer) rs.getObject("start_year"), rs.getBigDecimal("average_rating").doubleValue(),
                rs.getInt("num_votes"), rs.getBigDecimal("weighted_rating").doubleValue());
    }

    private static List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        return List.of((String[]) sqlArray.getArray());
    }
}
