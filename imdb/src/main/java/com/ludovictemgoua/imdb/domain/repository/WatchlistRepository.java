package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;

import java.util.Optional;

public interface WatchlistRepository {

    WatchlistView findOrCreateByUserId(int userId);

    Optional<WatchlistView> findByUserId(int userId);

    WriteResult addItem(int watchlistId, int titleId);

    WriteResult removeItem(int watchlistId, int titleId);

    WriteResult updateVisibility(int watchlistId, Visibility visibility, int expectedVersion);
}
