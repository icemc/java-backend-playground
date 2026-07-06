package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PersonCandidate(String id, String name, Integer birthYear, List<String> knownFor) {
}
