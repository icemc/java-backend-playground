package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface PersonRepository {

    List<PersonCandidate> findByName(String name);

    Optional<String> findNameById(int nconst);

    Map<Integer, String> findNamesByIds(Collection<Integer> nconsts);
}
