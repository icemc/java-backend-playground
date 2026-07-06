package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record TitleCore(String id, String primaryTitle, String originalTitle, String titleType,
                         Integer startYear, Integer endYear, Integer runtimeMinutes,
                         List<String> genres, Double averageRating, Integer numVotes) {
}
