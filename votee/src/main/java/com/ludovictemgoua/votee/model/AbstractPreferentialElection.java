package com.ludovictemgoua.votee.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class AbstractPreferentialElection<C extends Candidate, B extends Ballot<C, B>> implements Election<C, B, Winner<C>> {

    protected static final Rational MAJORITY_THRESHOLD = Rational.of(1, 2);

    protected final Map<C, Rational> countFirstVotes(List<B> ballots, List<C> candidates) {
        return countPreference(ballots, candidates, List::getFirst);
    }

    protected final List<Map.Entry<C, Rational>> resolveTies(
            List<Map.Entry<C, Rational>> sortedScores, TieResolver<C> tieResolver) {
        List<Map.Entry<C, Rational>> result = new ArrayList<>();
        int i = 0;
        while (i < sortedScores.size()) {
            Rational score = sortedScores.get(i).getValue();
            int j = i;
            while (j < sortedScores.size() && sortedScores.get(j).getValue().equals(score)) {
                j++;
            }
            result.addAll(tieResolver.resolve(sortedScores.subList(i, j)));
            i = j;
        }
        return result;
    }

    protected final Map<C, Rational> countLastVotes(List<B> ballots, List<C> candidates) {
        return countPreference(ballots, candidates, List::getLast);
    }

    private Map<C, Rational> countPreference(
            List<B> ballots, List<C> candidates, Function<List<C>, C> pick) {
        Map<C, Rational> scores = new LinkedHashMap<>();
        for (B ballot : ballots) {
            List<C> valid = ballot.preferences().stream().filter(candidates::contains).toList();
            if (!valid.isEmpty()) {
                C candidate = pick.apply(valid);
                scores.merge(candidate, ballot.weight(), Rational::add);
            }
        }
        return scores;
    }
}
