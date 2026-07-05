package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Veto<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Veto<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new Veto<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Map<C, Rational> scores = new LinkedHashMap<>();
        for (B ballot : ballots) {
            List<C> eligible = ballot.preferences().stream().filter(candidates::contains).toList();
            for (int i = 0; i < eligible.size(); i++) {
                // Every eligible preference scores a point except the ballot's last eligible choice,
                // its veto - unless the ballot only has one eligible candidate, which isn't vetoed.
                boolean isVetoed = i == eligible.size() - 1 && eligible.size() > 1;
                if (!isVetoed) {
                    scores.merge(eligible.get(i), Rational.ONE, Rational::add);
                }
            }
        }
        List<Map.Entry<C, Rational>> sorted = scores.entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue().reversed())
                .toList();
        return resolveTies(sorted, tieResolver).stream()
                .limit(vacancies)
                .map(Winner::of)
                .toList();
    }
}
