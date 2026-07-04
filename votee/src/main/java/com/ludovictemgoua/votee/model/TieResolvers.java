package com.ludovictemgoua.votee.model;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class TieResolvers {
    private TieResolvers() {
    }

    public static <C extends Candidate> TieResolver<C> doNothing() {
        return (List<Map.Entry<C, Rational>> tiedScores) -> tiedScores;
    }

    public static <C extends Candidate> TieResolver<C> random() {
        return (List<Map.Entry<C, Rational>> tied) -> {
            List<Map.Entry<C, Rational>> shuffled = new ArrayList<>(tied);
            Collections.shuffle(shuffled);
            return shuffled;
        };
    }

    public static <C extends Candidate> TieResolver<C> reverse() {
        return (List<Map.Entry<C, Rational>> tied) -> {
            List<Map.Entry<C, Rational>> reversed = new ArrayList<>(tied);
            Collections.reverse(reversed);
            return reversed;
        };
    }
}
