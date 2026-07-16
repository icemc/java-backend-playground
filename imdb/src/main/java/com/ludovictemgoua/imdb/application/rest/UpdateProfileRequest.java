package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String displayName, String bio, int version) {
}
