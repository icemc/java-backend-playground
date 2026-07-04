package com.ludovictemgoua.votee.model;

import java.util.Collection;
import java.util.List;

public interface Ballot <C extends Candidate, SELF extends Ballot<C, SELF>> {
    int id();
    Rational weight();
    List<C> preferences();

    /**
     * Filters preferences to exclude the specified candidates and returns a new ballot with the remaining preferences.
     * @param candidates the candidates to exclude
     * @return a new ballot with the specified candidates excluded
     */
    SELF exclude(Collection<? extends C> candidates);

    /**
     *  Filters the preferences to include only the specified candidates and returns a new ballot with the filtered preferences.
     *
     * @param candidates the candidates to include
     * @return a new ballot with the filtered preferences
     */
    SELF include(Collection<? extends C> candidates);
}

