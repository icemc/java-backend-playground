package com.ludovictemgoua.votee.algorithms;

import com.ludovictemgoua.votee.model.PreferentialBallot;
import com.ludovictemgoua.votee.model.PreferentialCandidate;
import com.ludovictemgoua.votee.model.Rational;
import com.ludovictemgoua.votee.model.Winner;
import com.ludovictemgoua.votee.support.FixtureLoader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoombsTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void matchesTheScalaReferenceOnTheFixtureData() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Coombs.elect(ballots, candidates, 1);

        assertThat(winners).extracting(winner -> winner.candidate().id()).containsExactly("a");
    }

    @Test
    void eliminatesTheMostLastRankedCandidateUntilAMajorityEmerges() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b, c)),
                PreferentialBallot.of(2, List.of(b, c, a)),
                PreferentialBallot.of(3, List.of(c, a, b))
        );

        // Round 1 first-preferences (a=1, b=1, c=1) have no majority; c is ranked last twice (most
        // disliked) and is eliminated. Round 2 among [a, b]: a=2, b=1 - a clears the 1.5 majority.
        List<Winner<PreferentialCandidate>> winners = Coombs.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new Winner<>(a, Rational.whole(2)));
    }
}
