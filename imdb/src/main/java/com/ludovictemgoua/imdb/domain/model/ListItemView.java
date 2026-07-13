package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record ListItemView(String titleId, String primaryTitle, Instant addedAt) {
}
