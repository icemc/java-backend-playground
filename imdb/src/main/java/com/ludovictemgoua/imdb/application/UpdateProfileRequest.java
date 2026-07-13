package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(@NotBlank String displayName, String bio, int version) {
}
