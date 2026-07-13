package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record WatchlistItemView(String titleId, String primaryTitle, Instant addedAt) {
}
