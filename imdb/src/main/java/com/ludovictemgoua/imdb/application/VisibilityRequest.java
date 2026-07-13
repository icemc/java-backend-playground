package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotNull;

public record VisibilityRequest(@NotNull Visibility visibility) {
}
