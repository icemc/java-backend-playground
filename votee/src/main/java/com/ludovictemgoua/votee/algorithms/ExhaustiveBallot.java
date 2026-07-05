package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ExhaustiveBallot<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        return new ExhaustiveBallot<C, B>().run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new ExhaustiveBallot<C, B>().run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    /**
     * Repeatedly excludes the lowest first-preference scorer from both the candidate and ballot
     * lists, until only two candidates remain, then returns the higher scorer of the two. Ignores
     * {@code vacancies}, matching the reference implementation, which only ever elects one winner.
     * Unlike the reference implementation (which decides both elimination and the final winner via a
     * plain sort, ignoring its own tieResolver parameter entirely), ties here are resolved through the
     * given {@code tieResolver} at both decision points.
     */
    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        List<C> remainingCandidates = new ArrayList<>(candidates);
        List<B> remainingBallots = new ArrayList<>(ballots);
        Map<C, Rational> scores = countFirstVotes(remainingBallots, remainingCandidates);

        while (scores.size() > 2) {
            C loser = resolveTies(ascending(scores), tieResolver).get(0).getKey();
            remainingCandidates.remove(loser);
            remainingBallots = remainingBallots.stream().map(ballot -> ballot.exclude(List.of(loser))).toList();
            scores = countFirstVotes(remainingBallots, remainingCandidates);
        }

        List<Map.Entry<C, Rational>> finalScores = resolveTies(ascending(scores), tieResolver);
        return List.of(Winner.of(finalScores.get(finalScores.size() - 1)));
    }

    private static <C extends Candidate> List<Map.Entry<C, Rational>> ascending(Map<C, Rational> scores) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<C, Rational>comparingByValue())
                .toList();
    }
}
