package com.ludovictemgoua.votee.model;

import java.util.Map;

public record Winner<C extends Candidate>(C candidate, Rational score) {
    public static <C extends Candidate> Winner<C> of(Map.Entry<C, Rational> entry) {
        return new Winner<>(entry.getKey(), entry.getValue());
    }
}