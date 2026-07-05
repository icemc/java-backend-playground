package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaldwinTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void matchesTheScalaReferenceOnTheFixtureData() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Baldwin.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("a");
    }

    @Test
    void aSingleRemainingCandidateWinsWithoutAnEliminationRound() {
        List<PreferentialCandidate> candidates = List.of(a);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a))
        );

        List<Winner<PreferentialCandidate>> winners = Baldwin.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new Winner<>(a, Rational.ZERO));
    }

    @Test
    void eliminatesTheLowestBordaScorerEachRoundUntilOneCandidateRemains() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c)),
                PreferentialBallot.of(2, List.of(a, b, c)),
                PreferentialBallot.of(3, List.of(b, c, a)),
                PreferentialBallot.of(4, List.of(c, a, b))
        );

        // Round 1 Borda scores (a=5, b=4, c=3) eliminate c.
        // Round 2 Borda scores among [a, b] (a=3, b=1) eliminate b, leaving a.
        List<Winner<PreferentialCandidate>> winners = Baldwin.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new Winner<>(a, Rational.ZERO));
    }
}
