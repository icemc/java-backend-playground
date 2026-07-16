package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record PrincipalRequest(@NotBlank String personId, @NotBlank String category, String job,
                                List<String> characters, int ordering) {
}
