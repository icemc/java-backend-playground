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

class ApprovalTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    /**
     * Every ballot in this fixture ranks all 4 candidates in full, so approval degenerates into a
     * perfect 4-way tie (each candidate approved on all 9 ballots). votee-scala's own ApprovalSpec
     * asserts a single winner ("d") out of that tie, but which candidate comes first is an artifact
     * of each language's internal map iteration order, not of the algorithm - Scala's mutable.HashMap
     * bucket order versus Java's LinkedHashMap insertion order have no reason to agree. Asserting the
     * (correct, tied) score is the meaningful check here; asserting one specific winner would just be
     * pinning down an implementation detail. See the LLD's "Determinism and Tie-Break Ordering" section.
     */
    @Test
    void everyCandidateEndsUpTiedWhenAllBallotsRankAllCandidates() {
        List<PreferentialCandidate> candidates = FixtureLoader.candidates("01-candidates.json");
        List<PreferentialBallot<PreferentialCandidate>> ballots = FixtureLoader.ballots("03-ballots.json");

        List<Winner<PreferentialCandidate>> winners = Approval.elect(ballots, candidates, candidates.size());

        assertThat(winners).extracting(Winner::score).containsOnly(Rational.whole(9));
        assertThat(winners).extracting(winner -> winner.candidate().id())
                .containsExactlyInAnyOrder("a", "b", "c", "d");
    }

    @Test
    void everyListedPreferenceCountsAsAFullApprovalVote() {
        List<PreferentialCandidate> candidates = List.of(a, b, c);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, b)),
                PreferentialBallot.of(2, List.of(a)),
                PreferentialBallot.of(3, List.of(a, c))
        );

        List<Winner<PreferentialCandidate>> winners = Approval.elect(ballots, candidates, 1);

        assertThat(winners).containsExactly(new PreferentialWinner<>(a, Rational.whole(3)));
    }

    @Test
    void aPreferenceForACandidateNotInTheEligibleListNeverCounts() {
        List<PreferentialCandidate> candidates = List.of(a, b);
        List<PreferentialBallot<PreferentialCandidate>> ballots = List.of(
                PreferentialBallot.of(1, List.of(a, c)),
                PreferentialBallot.of(2, List.of(a, b))
        );

        List<Winner<PreferentialCandidate>> winners = Approval.elect(ballots, candidates, candidates.size());

        assertThat(winners).containsExactly(
                new PreferentialWinner<>(a, Rational.whole(2)),
                new PreferentialWinner<>(b, Rational.whole(1))
        );
    }
}
