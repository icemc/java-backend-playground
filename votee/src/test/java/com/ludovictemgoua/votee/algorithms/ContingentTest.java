package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.PreferentialWinner;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only the immediate-majority path is covered here. The runoff/redistribution branch is gated by
 * comparing the leader's raw score to a flat 1/2 (see Contingent's own inline comment) rather than
 * to half of ballots.size(), which is under separate review - see the PDD/LLD discussion - so it is
 * deliberately left untested until that comparison is settled.
 */
class ContingentTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");

    @Test
    void matchesTheScalaReferenceOnTheFixtureData() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Contingent.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("a");
    }

    @Test
    void picksTheImmediateFirstPreferenceLeaderWhenItsScoreExceedsOneHalf() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b)),
                PreferentialBallot.of(2, List.of(a, b)),
                PreferentialBallot.of(3, List.of(b, a))
        );

        List<Winner<PreferentialCandidate>> winners = Contingent.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.whole(2)));
    }

    @Test
    void returnsNoWinnerWhenNoCandidateReceivesAnEligibleFirstPreferenceVote() {
        List<PreferentialCandidate> candidates = List.of();
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of();

        List<Winner<PreferentialCandidate>> winners = Contingent.elect(ballots, candidates, 1);

        assertThat(winners).isEmpty();
    }

    /**
     * Needs fractional weights: with integer weights any nonzero score already clears the flat 1/2
     * threshold (see Contingent's own inline comment), short-circuiting before the runoff loop below
     * ever runs. Keeping every ballot's weight at 1/4 keeps the leader's raw score at or under 1/2, so
     * this actually reaches the loop - and the empty-preference ballot inside it - unlike a normal
     * integer-weight scenario would.
     */
    @Test
    void skipsAnEmptyPreferenceBallotDuringTheRunoffInsteadOfThrowing() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                new PreferentialBallot<>(1, Rational.of(1, 4), List.of(a)),
                new PreferentialBallot<>(2, Rational.of(1, 4), List.of(b)),
                new PreferentialBallot<>(3, Rational.of(1, 4), List.of())
        );

        List<Winner<PreferentialCandidate>> winners = Contingent.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.of(1, 4)));
    }
}
