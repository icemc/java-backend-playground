package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BordaCount<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new BordaCount<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new BordaCount<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        List<Map.Entry<C, Rational>> sorted = bordaScores(ballots, candidates).entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        return resolveTies(sorted, tieResolver).stream()
                .limit(vacancies)
                .map(Winner::of)
                .toList();
    }

    /**
     * Scores each candidate by rank position (candidates.size() - 1 - index) on every ballot,
     * weighted by the ballot's weight. Package-private so Baldwin can reuse it per elimination round.
     */
    static <C extends Candidate, B extends Ballot<C, B>> Map<C, Rational> bordaScores(List<B> ballots, List<C> candidates) {
        Map<C, Rational> scores = new LinkedHashMap<>();
        for (B ballot : ballots) {
            List<C> eligible = ballot.preferences().stream().filter(candidates::contains).toList();
            for (int i = 0; i < eligible.size(); i++) {
                Rational points = Rational.whole(candidates.size() - 1L - i).multiply(ballot.weight());
                scores.merge(eligible.get(i), points, Rational::add);
            }
        }
        return scores;
    }
}
