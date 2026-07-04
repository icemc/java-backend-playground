package com.ludovictemgoua.votee.model;

import java.util.List;

public interface Election<C extends Candidate, B extends Ballot<C, B>, W extends Winner<C>> {
    List<W> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver);

    default List<W> run(List<B> ballots, List<C> candidates, int vacancies) {
        return run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }
}

