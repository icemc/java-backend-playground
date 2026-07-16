package com.ludovictemgoua.imdb.application.rest;

import java.util.List;

public record PatchPersonRequest(String primaryName, Integer birthYear, Integer deathYear,
                                  List<String> primaryProfession, int version) {
}
