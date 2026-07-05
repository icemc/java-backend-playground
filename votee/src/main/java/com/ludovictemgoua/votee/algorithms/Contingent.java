package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Contingent<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Contingent<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new Contingent<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    /** Ignores {@code vacancies}, matching the reference implementation, which only ever elects one winner. */
    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Map<C, Rational> scores = new LinkedHashMap<>(countFirstVotes(ballots, candidates));
        List<Map.Entry<C, Rational>> sorted = resolveTies(descending(scores), tieResolver);

        // Matches the reference implementation exactly: the leader's raw first-preference score is
        // compared against the flat 1/2 constant, not against half of ballots.size(). In practice this
        // means the runoff branch below is only reached when ballot weights are unusually small.
        if (sorted.get(0).getValue().compareTo(MAJORITY_THRESHOLD) > 0) {
            return List.of(Winner.of(sorted.get(0)));
        }

        List<C> topTwo = sorted.stream().limit(2).map(Map.Entry::getKey).toList();
        for (B ballot : ballots) {
            if (!topTwo.contains(ballot.preferences().getFirst())) {
                ballot.preferences().stream()
                        .filter(topTwo::contains)
                        .findFirst()
                        .ifPresent(candidate -> scores.merge(candidate, ballot.weight(), Rational::add));
            }
        }

        return List.of(Winner.of(resolveTies(descending(scores), tieResolver).get(0)));
    }

    private static <C extends Candidate> List<Map.Entry<C, Rational>> descending(Map<C, Rational> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
    }
}
