package com.ludovictemgoua.imdb.application;

import java.util.List;

public record PatchPersonRequest(String primaryName, Integer birthYear, Integer deathYear,
                                  List<String> primaryProfession, int version) {
}
