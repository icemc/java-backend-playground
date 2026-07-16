package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreatePersonRequest(@NotBlank String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession) {
}
