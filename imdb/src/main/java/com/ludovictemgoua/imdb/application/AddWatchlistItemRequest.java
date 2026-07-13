package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record AddWatchlistItemRequest(@NotBlank String titleId) {
}
