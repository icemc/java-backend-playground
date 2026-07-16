package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.rest.SixDegreesResult;
import com.ludovictemgoua.imdb.domain.model.PersonCandidate;

import java.util.List;

public sealed interface SixDegreesOutcome {
    record Found(SixDegreesResult result) implements SixDegreesOutcome {
    }
    record Ambiguous(String query, List<PersonCandidate> candidates) implements SixDegreesOutcome {
    }
    record PersonNotFound(String query) implements SixDegreesOutcome {
    }
}
