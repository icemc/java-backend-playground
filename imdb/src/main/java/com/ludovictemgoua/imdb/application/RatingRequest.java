package com.ludovictemgoua.imdb.application;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public record RatingRequest(@DecimalMin("0.0") @DecimalMax("10.0") double averageRating,
                             @Min(0) int numVotes) {
}
