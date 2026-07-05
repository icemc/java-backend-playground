package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.PreferentialWinner;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.TieResolvers;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MajorityTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void picksTheFixtureWinnerJustLikeTheScalaReference() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Majority.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("a");
    }

    @Test
    void returnsNoWinnerWhenTheTopTwoCandidatesSplitTheBallotsExactlyInHalf() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b)),
                PreferentialBallot.of(2, List.of(b, a))
        );

        List<Winner<PreferentialCandidate>> winners = Majority.elect(ballots, candidates, 1);

        assertThat(winners).isEmpty();
    }

    @Test
    void picksTheCandidateWithStrictlyMoreThanHalfTheFirstPreferenceVotes() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c)),
                PreferentialBallot.of(2, List.of(a, b, c)),
                PreferentialBallot.of(3, List.of(a, c, b)),
                PreferentialBallot.of(4, List.of(b, a, c)),
                PreferentialBallot.of(5, List.of(c, a, b))
        );

        List<Winner<PreferentialCandidate>> withDefaultResolver = Majority.elect(ballots, candidates, 1);
        List<Winner<PreferentialCandidate>> withExplicitResolver =
                Majority.elect(ballots, candidates, 1, TieResolvers.doNothing());

        assertThat(withDefaultResolver).containsExactly(new PreferentialWinner<>(a, Rational.whole(3)));
        assertThat(withExplicitResolver).isEqualTo(withDefaultResolver);
    }
}
