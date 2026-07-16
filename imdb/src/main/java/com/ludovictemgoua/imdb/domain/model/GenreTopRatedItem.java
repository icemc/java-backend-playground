package com.ludovictemgoua.imdb.domain.model;

public record GenreTopRatedItem(String id, String primaryTitle, Integer startYear, double averageRating,
                                 int numVotes, double weightedRating) {
}
