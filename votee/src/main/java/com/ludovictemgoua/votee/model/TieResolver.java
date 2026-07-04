package com.ludovictemgoua.votee.model;

import java.util.List;
import java.util.Map;

public interface TieResolver<C extends Candidate> {
    List<Map.Entry<C, Rational>> resolve(List<Map.Entry<C, Rational>> tiedScores);
}

