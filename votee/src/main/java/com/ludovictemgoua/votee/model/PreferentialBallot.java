package com.ludovictemgoua.votee.model;

import java.util.Collection;
import java.util.List;

public record PreferentialBallot<C extends Candidate>(int id, Rational weight,
                                                      List<C> preferences) implements Ballot<C, PreferentialBallot<C>> {

    public PreferentialBallot {
        preferences = List.copyOf(preferences);
    }

    public static <C extends Candidate> PreferentialBallot<C> of(int id, List<C> preferences) {
        return new PreferentialBallot<>(id, Rational.ONE, preferences);
    }

    @Override
    public PreferentialBallot<C> exclude(Collection<? extends C> candidates) {
        return new PreferentialBallot<>(id, weight, preferences.stream()
                .filter(candidate -> !candidates.contains(candidate))
                .toList());
    }

    @Override
    public PreferentialBallot<C> include(Collection<? extends C> candidates) {
        return new PreferentialBallot<>(id, weight, preferences.stream()
                .filter(candidates::contains)
                .toList());
    }
}
