package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

public record AddWatchlistItemRequest(@NotBlank String titleId) {
}
