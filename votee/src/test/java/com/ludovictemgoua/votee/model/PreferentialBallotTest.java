package com.ludovictemgoua.votee.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreferentialBallotTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    @Test
    void excludeDropsTheGivenCandidatesButKeepsTheRest() {
        PreferentialBallot<PreferentialCandidate> ballot = PreferentialBallot.of(1, List.of(a, b, c));

        PreferentialBallot<PreferentialCandidate> filtered = ballot.exclude(List.of(b));

        assertThat(filtered.preferences()).containsExactly(a, c);
        assertThat(filtered.id()).isEqualTo(ballot.id());
        assertThat(filtered.weight()).isEqualTo(ballot.weight());
    }

    @Test
    void excludeIsANoOpWhenNoneOfTheGivenCandidatesAreOnTheBallot() {
        PreferentialBallot<PreferentialCandidate> ballot = PreferentialBallot.of(1, List.of(a, b));

        PreferentialBallot<PreferentialCandidate> filtered = ballot.exclude(List.of(c));

        assertThat(filtered.preferences()).containsExactly(a, b);
    }

    @Test
    void includeRetainsOnlyTheGivenCandidates() {
        PreferentialBallot<PreferentialCandidate> ballot = PreferentialBallot.of(1, List.of(a, b, c));

        PreferentialBallot<PreferentialCandidate> filtered = ballot.include(List.of(a, c));

        assertThat(filtered.preferences()).containsExactly(a, c);
    }

    @Test
    void preferencesAreDefensivelyCopiedOnConstruction() {
        List<PreferentialCandidate> mutablePreferences = new ArrayList<>(List.of(a, b));

        PreferentialBallot<PreferentialCandidate> ballot = PreferentialBallot.of(1, mutablePreferences);
        mutablePreferences.add(c);

        assertThat(ballot.preferences()).containsExactly(a, b);
    }

    @Test
    void ofDefaultsToAWeightOfOne() {
        PreferentialBallot<PreferentialCandidate> ballot = PreferentialBallot.of(1, List.of(a, b));

        assertThat(ballot.weight()).isEqualTo(Rational.ONE);
    }
}
