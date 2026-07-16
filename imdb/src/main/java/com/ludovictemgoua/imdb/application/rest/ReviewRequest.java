package com.ludovictemgoua.imdb.application.rest;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReviewRequest(@Min(1) @Max(10) int rating, String body, int version) {
}
