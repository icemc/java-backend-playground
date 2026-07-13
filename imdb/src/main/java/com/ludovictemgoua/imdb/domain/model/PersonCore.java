package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record PersonCore(String id, String primaryName, Integer birthYear, Integer deathYear,
                          List<String> primaryProfession, int version) {
}
