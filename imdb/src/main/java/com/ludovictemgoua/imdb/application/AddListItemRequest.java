package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record AddListItemRequest(@NotBlank String titleId) {
}
