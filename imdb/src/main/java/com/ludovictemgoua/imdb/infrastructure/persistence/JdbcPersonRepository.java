package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class JdbcPersonRepository implements PersonRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcPersonRepository.class);

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPersonRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PersonCandidate> findByName(String name) {
        String sql = """
                SELECT nconst, primary_name, birth_year, known_for_titles
                FROM name_basics
                WHERE primary_name % :name AND deleted_at IS NULL
                ORDER BY similarity(primary_name, :name) DESC
                LIMIT 10
                """;
        List<PersonCandidate> candidates = jdbc.query(sql, Map.of("name", name), JdbcPersonRepository::mapCandidate);
        log.debug("person lookup: name={} candidateCount={}", name, candidates.size());
        return candidates;
    }

    @Override
    public Optional<String> findNameById(int nconst) {
        return jdbc.query("SELECT primary_name FROM name_basics WHERE nconst = :nconst",
                        Map.of("nconst", nconst), (rs, rowNum) -> rs.getString("primary_name"))
                .stream().findFirst();
    }

    @Override
    public Map<Integer, String> findNamesByIds(Collection<Integer> nconsts) {
        if (nconsts.isEmpty()) return Map.of();
        String sql = "SELECT nconst, primary_name FROM name_basics WHERE nconst IN (:nconsts)";
        List<Map.Entry<Integer, String>> rows = jdbc.query(sql, Map.of("nconsts", nconsts),
                (rs, rowNum) -> Map.entry(rs.getInt("nconst"), rs.getString("primary_name")));
        return rows.stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    @Override
    public PersonCore insertPerson(String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession) {
        String sql = """
                INSERT INTO name_basics (nconst, primary_name, birth_year, death_year, primary_profession)
                VALUES (nextval('person_id_seq'), :primaryName, :birthYear, :deathYear, :primaryProfession)
                RETURNING nconst
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryName", primaryName).addValue("birthYear", birthYear)
                .addValue("deathYear", deathYear)
                .addValue("primaryProfession", primaryProfession.toArray(new String[0]), java.sql.Types.ARRAY, "text");
        int nconst = jdbc.queryForObject(sql, params, Integer.class);
        return findCore(nconst).orElseThrow();
    }

    @Override
    public Optional<PersonCore> findCore(int nconst) {
        String sql = """
                SELECT nconst, primary_name, birth_year, death_year, primary_profession, version
                FROM name_basics WHERE nconst = :nconst AND deleted_at IS NULL
                """;
        return jdbc.query(sql, Map.of("nconst", nconst), JdbcPersonRepository::mapCore).stream().findFirst();
    }

    @Override
    public WriteResult updatePerson(int nconst, String primaryName, Integer birthYear, Integer deathYear,
                                    List<String> primaryProfession, int expectedVersion) {
        if (findCore(nconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        String sql = """
                UPDATE name_basics
                SET primary_name = :primaryName, birth_year = :birthYear, death_year = :deathYear,
                    primary_profession = :primaryProfession, version = version + 1
                WHERE nconst = :nconst AND version = :expectedVersion AND deleted_at IS NULL
                """;
        var params = new MapSqlParameterSource()
                .addValue("primaryName", primaryName).addValue("birthYear", birthYear)
                .addValue("deathYear", deathYear)
                .addValue("primaryProfession", primaryProfession.toArray(new String[0]), java.sql.Types.ARRAY, "text")
                .addValue("nconst", nconst).addValue("expectedVersion", expectedVersion);
        return jdbc.update(sql, params) == 0 ? WriteResult.VERSION_CONFLICT : WriteResult.SUCCESS;
    }

    @Override
    public WriteResult softDeletePerson(int nconst) {
        if (findCore(nconst).isEmpty()) {
            return WriteResult.NOT_FOUND;
        }
        jdbc.update("UPDATE name_basics SET deleted_at = now() WHERE nconst = :nconst", Map.of("nconst", nconst));
        return WriteResult.SUCCESS;
    }

    private static PersonCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        Array knownForArr = rs.getArray("known_for_titles");
        List<String> knownFor = knownForArr == null ? List.of()
                : Arrays.stream((Integer[]) knownForArr.getArray())
                        .filter(Objects::nonNull).map(ImdbIds::formatTitleId).limit(3).toList();
        return new PersonCandidate(
                ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                (Integer) rs.getObject("birth_year"), knownFor);
    }

    private static PersonCore mapCore(ResultSet rs, int rowNum) throws SQLException {
        return new PersonCore(ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                (Integer) rs.getObject("birth_year"), (Integer) rs.getObject("death_year"),
                toStringList(rs.getArray("primary_profession")), rs.getInt("version"));
    }

    private static List<String> toStringList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        return List.of((String[]) sqlArray.getArray());
    }
}
