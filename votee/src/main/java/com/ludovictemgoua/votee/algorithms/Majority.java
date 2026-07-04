package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.List;
import java.util.Map;

public final class Majority<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Majority<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(List<B> ballots, List<C> candidates, int vacancies) {
        return new Majority<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Rational threshold = Rational.whole(ballots.size()).multiply(MAJORITY_THRESHOLD);
        List<Map.Entry<C, Rational>> sorted = countFirstVotes(ballots, candidates).entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        return resolveTies(sorted, tieResolver).stream()
                .filter(e -> e.getValue().compareTo(threshold) > 0)
                .limit(vacancies)
                .map(Winner::of)
                .toList();
    }
}
