package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

public record AddListItemRequest(@NotBlank String titleId) {
}
