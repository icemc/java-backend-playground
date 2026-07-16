package com.ludovictemgoua.imdb.application.rest;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotNull;

public record VisibilityRequest(@NotNull Visibility visibility) {
}
