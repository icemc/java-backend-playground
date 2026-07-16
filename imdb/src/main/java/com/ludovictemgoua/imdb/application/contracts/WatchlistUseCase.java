package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;

import java.util.Optional;

public interface WatchlistUseCase {

    WatchlistView getOwn(int userId);

    WatchlistView getForUser(Optional<Integer> viewerUserId, int targetUserId);

    void addItem(int userId, String titleId);

    void removeItem(int userId, String titleId);

    void updateVisibility(int userId, Visibility visibility);
}
