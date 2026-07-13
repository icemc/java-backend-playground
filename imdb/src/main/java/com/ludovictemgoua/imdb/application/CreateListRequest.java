package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateListRequest(@NotBlank String name, @NotNull Visibility visibility) {
}
