package com.ludovictemgoua.imdb.domain.model;

import java.util.List;

public record WatchlistView(int id, int userId, Visibility visibility, int version, List<WatchlistItemView> items) {
}
