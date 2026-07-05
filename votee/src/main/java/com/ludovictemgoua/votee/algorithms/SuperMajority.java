package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.*;

import java.util.List;
import java.util.Map;

public final class SuperMajority<C extends Candidate, B extends Ballot<C, B>> extends AbstractPreferentialElection<C, B> {

    private final Rational majorityPercentage;

    public SuperMajority(Rational majorityPercentage) {
        if (majorityPercentage.compareTo(MAJORITY_THRESHOLD) < 0 || majorityPercentage.compareTo(Rational.ONE) > 0) {
            throw new IllegalArgumentException("majorityPercentage must be between 1/2 and 1");
        }
        this.majorityPercentage = majorityPercentage;
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, Rational majorityPercentage, TieResolver<C> tieResolver) {
        return new SuperMajority<C, B>(majorityPercentage).run(ballots, candidates, vacancies, tieResolver);
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies, Rational majorityPercentage) {
        return new SuperMajority<C, B>(majorityPercentage).run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    public static <C extends Candidate, B extends Ballot<C, B>> List<Winner<C>> elect(
            List<B> ballots, List<C> candidates, int vacancies) {
        return new SuperMajority<C, B>(MAJORITY_THRESHOLD).run(ballots, candidates, vacancies, TieResolvers.doNothing());
    }

    @Override
    public List<Winner<C>> run(List<B> ballots, List<C> candidates, int vacancies, TieResolver<C> tieResolver) {
        Rational threshold = Rational.whole(ballots.size()).multiply(majorityPercentage);
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
