package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class Baldwin<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Baldwin<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new Baldwin<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    /**
     * Repeatedly eliminates the lowest Borda scorer until one candidate remains. Ignores
     * {@code vacancies}, matching the reference implementation, which only ever elects one winner.
     */
    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        List<C> remaining = new ArrayList<>(candidates);
        while (remaining.size() > 1) {
            List<Map.Entry<C, Rational>> ascending = BordaCount.bordaScores(ballots, remaining).entrySet().stream()
                    .sorted(Map.Entry.<C, Rational>comparingByValue())
                    .toList();
            C lowestScorer = resolveTies(ascending, tieResolver).get(0).getKey();
            remaining.remove(lowestScorer);
        }
        return BordaCount.bordaScores(ballots, remaining).entrySet().stream()
                .map(Winner::of)
                .toList();
    }
}
