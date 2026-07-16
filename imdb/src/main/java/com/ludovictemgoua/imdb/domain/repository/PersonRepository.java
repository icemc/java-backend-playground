package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.PersonCore;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PersonRepository {

    List<PersonCandidate> findByName(String name);

    Optional<String> findNameById(int nconst);

    Map<Integer, String> findNamesByIds(Collection<Integer> nconsts);

    PersonCore insertPerson(String primaryName, Integer birthYear, Integer deathYear, List<String> primaryProfession);

    Optional<PersonCore> findCore(int nconst);

    WriteResult updatePerson(int nconst, String primaryName, Integer birthYear, Integer deathYear,
                             List<String> primaryProfession, int expectedVersion);

    WriteResult softDeletePerson(int nconst);
}
