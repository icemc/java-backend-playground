package com.ludovictemgoua.imdb.domain.model;

import java.time.Instant;

public record Review(int id, int userId, int titleId, int rating, String body, int version,
                      Instant createdAt, Instant updatedAt) {
}
