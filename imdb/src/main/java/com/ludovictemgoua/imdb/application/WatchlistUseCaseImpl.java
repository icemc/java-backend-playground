package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class WatchlistUseCaseImpl implements WatchlistUseCase {

    private final WatchlistRepository watchlistRepository;

    public WatchlistUseCaseImpl(WatchlistRepository watchlistRepository) {
        this.watchlistRepository = watchlistRepository;
    }

    @Override
    public WatchlistView getOwn(int userId) {
        return watchlistRepository.findOrCreateByUserId(userId);
    }

    @Override
    public WatchlistView getForUser(Optional<Integer> viewerUserId, int targetUserId) {
        WatchlistView view = watchlistRepository.findByUserId(targetUserId)
                .orElseThrow(() -> new NotFoundException("No watchlist for that user"));
        boolean isOwner = viewerUserId.isPresent() && viewerUserId.get() == targetUserId;
        if (view.visibility() == Visibility.PRIVATE && !isOwner) {
            throw new NotFoundException("No watchlist for that user");
        }
        return view;
    }

    @Override
    public void addItem(int userId, String titleId) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        watchlistRepository.addItem(watchlist.id(), ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void removeItem(int userId, String titleId) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        watchlistRepository.removeItem(watchlist.id(), ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void updateVisibility(int userId, Visibility visibility) {
        var watchlist = watchlistRepository.findOrCreateByUserId(userId);
        var result = watchlistRepository.updateVisibility(watchlist.id(), visibility, watchlist.version());
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Watchlist was modified concurrently - retry");
        }
    }
}
