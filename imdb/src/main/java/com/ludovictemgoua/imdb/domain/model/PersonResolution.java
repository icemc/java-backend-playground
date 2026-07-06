package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public sealed interface PersonResolution {
    record Resolved(int nconst, String name) implements PersonResolution {
    }
    record Ambiguous(List<PersonCandidate> candidates) implements PersonResolution {
    }
    record NotFound() implements PersonResolution {
    }
}
