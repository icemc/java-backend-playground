package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BordaCountTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void matchesTheScalaReferenceOnTheFixtureData() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = BordaCount.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("a");
    }

    @Test
    void scoresByRankPositionAmongAllCandidates() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c))
        );

        List<Winner<PreferentialCandidate>> winners = BordaCount.elect(ballots, candidates, 3);

        assertThat(winners).containsExactly(
                new Winner<>(a, Rational.whole(2)),
                new Winner<>(b, Rational.whole(1)),
                new Winner<>(c, Rational.whole(0))
        );
    }

    @Test
    void ranksOnlyWithinTheGivenCandidateListEvenWhenABallotPrefersAnExcludedCandidate() {
        List<PreferentialCandidate> eligibleCandidates = List.of(a, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c))
        );

        List<Winner<PreferentialCandidate>> winners = BordaCount.elect(ballots, eligibleCandidates, 2);

        assertThat(winners).containsExactly(
                new Winner<>(a, Rational.whole(1)),
                new Winner<>(c, Rational.whole(0))
        );
    }
}
