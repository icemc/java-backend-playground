package com.ludovictemgoua.votee.model;

import java.util.Map;

/**
 * Contract for an election winner: a candidate paired with their final score. Implemented as an
 * interface (rather than the {@code PreferentialWinner} record directly) so consumers who need
 * extra fields (rank, margin, district, and so on) can implement their own {@code Winner}, instead
 * of being constrained to the built-in record.
 */
public interface Winner<C extends Candidate> {
    C candidate();

    Rational score();

    static <C extends Candidate> Winner<C> of(Map.Entry<C, Rational> entry) {
        return new PreferentialWinner<>(entry.getKey(), entry.getValue());
    }
}
