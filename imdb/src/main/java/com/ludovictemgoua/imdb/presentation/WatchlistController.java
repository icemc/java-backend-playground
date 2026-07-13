package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.AddWatchlistItemRequest;
import com.ludovictemgoua.imdb.application.VisibilityRequest;
import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WatchlistController {

    private final WatchlistUseCase watchlistUseCase;

    public WatchlistController(WatchlistUseCase watchlistUseCase) {
        this.watchlistUseCase = watchlistUseCase;
    }

    @GetMapping("/api/v1/watchlist")
    public WatchlistView getOwn(Authentication authentication) {
        return watchlistUseCase.getOwn(CurrentUser.requireId(authentication));
    }

    @PostMapping("/api/v1/watchlist/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addItem(Authentication authentication, @Valid @RequestBody AddWatchlistItemRequest request) {
        watchlistUseCase.addItem(CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/api/v1/watchlist/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(Authentication authentication, @PathVariable String titleId) {
        watchlistUseCase.removeItem(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/watchlist/visibility")
    public void updateVisibility(Authentication authentication, @Valid @RequestBody VisibilityRequest request) {
        watchlistUseCase.updateVisibility(CurrentUser.requireId(authentication), request.visibility());
    }

    @GetMapping("/api/v1/users/{userId}/watchlist")
    public WatchlistView getForUser(Authentication authentication, @PathVariable int userId) {
        return watchlistUseCase.getForUser(CurrentUser.idOf(authentication), userId);
    }
}
