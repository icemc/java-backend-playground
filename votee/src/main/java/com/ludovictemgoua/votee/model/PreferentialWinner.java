package com.ludovictemgoua.votee.model;

/** The library's default {@link Winner} implementation: just a candidate and their final score. */
public record PreferentialWinner<C extends Candidate>(C candidate, Rational score) implements Winner<C> {
}
