package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record UpdatePersonRequest(@NotBlank String primaryName, Integer birthYear, Integer deathYear,
                                   List<String> primaryProfession, int version) {
}
