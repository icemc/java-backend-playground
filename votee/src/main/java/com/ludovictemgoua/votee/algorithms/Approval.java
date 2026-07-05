package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Approval<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new Approval<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new Approval<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Map<C, Rational> scores = new LinkedHashMap<>();
        for (B ballot : ballots) {
            ballot.preferences().stream()
                    .filter(candidates::contains)
                    .forEach(candidate -> scores.merge(candidate, ballot.weight(), Rational::add));
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
