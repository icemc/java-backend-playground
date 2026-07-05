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

class VetoTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    /**
     * Hand-tallying this fixture's last-place counts (d:1, a:1, b:3, c:4 out of 9 ballots) gives Veto
     * scores a=8, b=6, c=5, d=8 - a and d are genuinely tied for first. votee-scala's own VetoSpec
     * asserts a single winner ("d"), but which of the tied pair comes first is an artifact of each
     * language's internal map iteration order (Scala's mutable.HashMap bucket order vs. Java's
     * LinkedHashMap insertion order), not something the algorithm itself determines. See the LLD's
     * "Determinism and Tie-Break Ordering" section.
     */
    @Test
    void matchesTheScalaReferenceScoreOnTheFixtureDataEvenThoughTheTiedWinnerDiffers() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Veto.elect(ballots, candidates, 1);

        assertThat(winners).hasSize(1);
        assertThat(winners.get(0).score()).isEqualTo(Rational.whole(8));
        assertThat(winners.get(0).candidate().id()).isIn("a", "d");
    }

    @Test
    void everyPreferenceExceptTheLastOneScoresAPoint() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c)),
                PreferentialBallot.of(2, List.of(a, b, c)),
                PreferentialBallot.of(3, List.of(c, b, a))
        );

        List<Winner<PreferentialCandidate>> winners = Veto.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(b, Rational.whole(3)));
    }

    @Test
    void aSinglePreferenceBallotDoesNotVetoItsOnlyCandidate() {
        List<PreferentialCandidate> candidates = List.of(a);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a))
        );

        List<Winner<PreferentialCandidate>> winners = Veto.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.ONE));
    }

    @Test
    void vetoScoresAreFlatPointsNotWeightedByBallotWeight() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                new PreferentialBallot<>(1, Rational.whole(10), List.of(a, b))
        );

        List<Winner<PreferentialCandidate>> winners = Veto.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.ONE));
    }

    @Test
    void aCandidateNotInTheEligibleListNeverCountsAndTheVetoTargetsTheLastEligibleChoiceInstead() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c))
        );

        List<Winner<PreferentialCandidate>> winners = Veto.elect(ballots, candidates, candidates.size());

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.ONE));
    }
}
