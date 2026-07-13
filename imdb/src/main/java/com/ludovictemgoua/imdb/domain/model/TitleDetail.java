package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record TitleDetail(String id, String primaryTitle, String originalTitle, String titleType,
                           Integer startYear, Integer endYear, Integer runtimeMinutes,
                           List<String> genres, RatingView rating,
                           List<CreditedPerson> directors, List<CreditedPerson> writers,
                           List<CastMember> cast, int castTotalCount,
                           double userRatingAverage, int userRatingCount) {
}
