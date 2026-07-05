package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.TieResolvers;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExhaustiveBallotTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void matchesTheScalaReferenceOnTheFixtureData() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("01-ballots.json");

        List<Winner<PreferentialCandidate>> winners = ExhaustiveBallot.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("b");
    }

    @Test
    void whichTiedCandidateIsEliminatedFirstIsDecidedByTheGivenTieResolver() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c)),
                PreferentialBallot.of(2, List.of(b, c, a)),
                PreferentialBallot.of(3, List.of(c, a, b))
        );

        // Round 1 first-preferences are a three-way tie (a=1, b=1, c=1). doNothing keeps insertion
        // order (a, b, c) and eliminates a first, leaving b (2 votes) ahead of c (1) after ballot 3's
        // preference shifts. reverse flips the tied group and eliminates c first instead, leaving a
        // (2 votes) ahead of b (1) - a different final winner from the same tied starting scores.
        List<Winner<PreferentialCandidate>> withDoNothing =
                ExhaustiveBallot.elect(ballots, candidates, 1, TieResolvers.doNothing());
        List<Winner<PreferentialCandidate>> withReverse =
                ExhaustiveBallot.elect(ballots, candidates, 1, TieResolvers.reverse());

        assertThat(withDoNothing).containsExactly(new Winner<>(b, Rational.whole(2)));
        assertThat(withReverse).containsExactly(new Winner<>(a, Rational.whole(2)));
    }
}
