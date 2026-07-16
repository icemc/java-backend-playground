package com.ludovictemgoua.imdb.application.contracts;

public interface SixDegreesUseCase {

    SixDegreesOutcome compute(String queryA, String queryB, int maxDegree);
}
