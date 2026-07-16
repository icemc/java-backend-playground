package com.ludovictemgoua.imdb.application.rest;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record UpdateListRequest(@NotBlank String name, @NotNull Visibility visibility, int version) {
}
