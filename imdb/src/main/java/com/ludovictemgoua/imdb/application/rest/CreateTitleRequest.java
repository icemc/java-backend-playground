package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateTitleRequest(@NotBlank String primaryTitle, @NotBlank String originalTitle,
                                  @NotBlank String titleType, Integer startYear, Integer endYear,
                                  Integer runtimeMinutes, List<String> genres) {
}
