package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SuperMajorityTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");

    @Test
    void matchesTheScalaReferenceWhenNoCandidateClearsA60PercentThreshold() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners =
                SuperMajority.elect(ballots, candidates, 1, Rational.of(6, 10));

        assertThat(winners).isEmpty();
    }

    @Test
    void returnsNoWinnerWhenTheLeaderIsExactlyAtTheThreshold() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b)),
                PreferentialBallot.of(2, List.of(a, b)),
                PreferentialBallot.of(3, List.of(a, b)),
                PreferentialBallot.of(4, List.of(b, a))
        );

        List<Winner<PreferentialCandidate>> winners =
                SuperMajority.elect(ballots, candidates, 1, Rational.of(3, 4));

        assertThat(winners).isEmpty();
    }

    @Test
    void picksTheCandidateThatClearsTheGivenThreshold() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b)),
                PreferentialBallot.of(2, List.of(a, b)),
                PreferentialBallot.of(3, List.of(a, b)),
                PreferentialBallot.of(4, List.of(a, b)),
                PreferentialBallot.of(5, List.of(b, a))
        );

        List<Winner<PreferentialCandidate>> winners =
                SuperMajority.elect(ballots, candidates, 1, Rational.of(3, 4));

        assertThat(winners).containsExactly(new Winner<>(a, Rational.whole(4)));
    }

    @Test
    void rejectsAThresholdBelowOneHalf() {
        assertThatThrownBy(() -> new SuperMajority<PreferentialCandidate, PreferentialBallot<PreferentialCandidate>>(Rational.of(1, 3)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAThresholdAboveOne() {
        assertThatThrownBy(() -> new SuperMajority<PreferentialCandidate, PreferentialBallot<PreferentialCandidate>>(Rational.of(3, 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
