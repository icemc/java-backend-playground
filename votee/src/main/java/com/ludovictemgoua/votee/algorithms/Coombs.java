package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Coombs<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Coombs<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new Coombs<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    /**
     * Repeatedly eliminates the candidate ranked last most often, until one candidate holds a
     * strict majority of first-preference votes. Ignores {@code vacancies}, matching the reference
     * implementation, which only ever elects one winner. Requires ballots to rank every candidate.
     */
    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        List<C> remaining = new ArrayList<>(candidates);
        Rational threshold = MAJORITY_THRESHOLD.multiply(Rational.whole(ballots.size()));

        while (!remaining.isEmpty()) {
            List<Map.Entry<C, Rational>> overThreshold = countFirstVotes(ballots, remaining).entrySet().stream()
                    .filter(e -> e.getValue().compareTo(threshold) > 0)
                    .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                    .toList();
            if (!overThreshold.isEmpty()) {
                return resolveTies(overThreshold, tieResolver).stream().limit(1).map(Winner::of).toList();
            }

            List<Map.Entry<C, Rational>> mostDislikedFirst = countLastVotes(ballots, remaining).entrySet().stream()
                    .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                    .toList();
            C mostDisliked = resolveTies(mostDislikedFirst, tieResolver).get(0).getKey();
            remaining.remove(mostDisliked);
        }
        return List.of();
    }
}
