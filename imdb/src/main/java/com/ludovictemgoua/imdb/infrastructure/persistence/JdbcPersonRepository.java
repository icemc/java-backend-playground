package com.ludovictemgoua.imdb.infrastructure.persistence;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
                WHERE primary_name % :name
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

    private static PersonCandidate mapCandidate(ResultSet rs, int rowNum) throws SQLException {
        Array knownForArr = rs.getArray("known_for_titles");
        List<String> knownFor = knownForArr == null ? List.of()
                : Arrays.stream((Integer[]) knownForArr.getArray())
                        .filter(Objects::nonNull).map(ImdbIds::formatTitleId).limit(3).toList();
        return new PersonCandidate(
                ImdbIds.formatPersonId(rs.getInt("nconst")), rs.getString("primary_name"),
                (Integer) rs.getObject("birth_year"), knownFor);
    }
}
