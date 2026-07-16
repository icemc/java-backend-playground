package com.ludovictemgoua.imdb.domain.model;

public record TitleSummary(String id, String primaryTitle, String originalTitle, String titleType,
                            Integer startYear, Integer endYear) {
}
