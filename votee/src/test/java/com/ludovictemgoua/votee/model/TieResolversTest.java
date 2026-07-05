package com.ludovictemgoua.votee.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TieResolversTest {

    private final PreferentialCandidate a = new PreferentialCandidate("a", "A");
    private final PreferentialCandidate b = new PreferentialCandidate("b", "B");
    private final PreferentialCandidate c = new PreferentialCandidate("c", "C");

    private final List<Map.Entry<PreferentialCandidate, Rational>> tied = List.of(
            Map.entry(a, Rational.ONE),
            Map.entry(b, Rational.ONE),
            Map.entry(c, Rational.ONE)
    );

    @Test
    void doNothingReturnsTheOriginalOrderUnchanged() {
        List<Map.Entry<PreferentialCandidate, Rational>> resolved = TieResolvers.<PreferentialCandidate>doNothing().resolve(tied);

        assertThat(resolved).containsExactlyElementsOf(tied);
    }

    @Test
    void reverseFlipsTheOrder() {
        List<Map.Entry<PreferentialCandidate, Rational>> resolved = TieResolvers.<PreferentialCandidate>reverse().resolve(tied);

        assertThat(resolved).extracting(Map.Entry::getKey).containsExactly(c, b, a);
    }

    @Test
    void randomKeepsEveryEntryButMayReorderThem() {
        List<Map.Entry<PreferentialCandidate, Rational>> resolved = TieResolvers.<PreferentialCandidate>random().resolve(tied);

        assertThat(resolved).hasSameSizeAs(tied);
        assertThat(resolved).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(a, b, c);
    }
}
