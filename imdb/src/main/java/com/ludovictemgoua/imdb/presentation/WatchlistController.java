package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.rest.AddWatchlistItemRequest;
import com.ludovictemgoua.imdb.application.rest.VisibilityRequest;
import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@Tag(name = "Watchlist", description = "Per-user watchlist of titles to watch later")
public class WatchlistController {

    private final WatchlistUseCase watchlistUseCase;

    public WatchlistController(WatchlistUseCase watchlistUseCase) {
        this.watchlistUseCase = watchlistUseCase;
    }

    @GetMapping("/api/v1/watchlist")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "getOwnWatchlist", summary = "Get the authenticated user's watchlist",
            description = "Creates an empty PRIVATE watchlist on first access if the user doesn't have one yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's watchlist and its items"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public WatchlistView getOwn(Authentication authentication) {
        return watchlistUseCase.getOwn(CurrentUser.requireId(authentication));
    }

    @PostMapping("/api/v1/watchlist/items")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "addWatchlistItem", summary = "Add a title to the authenticated user's watchlist",
            description = "No-op if the title is already on the watchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Title added (or already present)"),
            @ApiResponse(responseCode = "400", description = "titleId is not a valid tt-prefixed id"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public void addItem(Authentication authentication, @Valid @RequestBody AddWatchlistItemRequest request) {
        watchlistUseCase.addItem(CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/api/v1/watchlist/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "removeWatchlistItem", summary = "Remove a title from the authenticated user's watchlist",
            description = "No-op if the title isn't on the watchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Title removed (or was never present)"),
            @ApiResponse(responseCode = "400", description = "titleId is not a valid tt-prefixed id"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public void removeItem(Authentication authentication,
                           @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        watchlistUseCase.removeItem(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/watchlist/visibility")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateWatchlistVisibility", summary = "Change the authenticated user's watchlist visibility",
            description = "PRIVATE (the default) or PUBLIC; a PUBLIC watchlist becomes viewable by anyone "
                    + "via getUserWatchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Visibility updated"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public void updateVisibility(Authentication authentication, @Valid @RequestBody VisibilityRequest request) {
        watchlistUseCase.updateVisibility(CurrentUser.requireId(authentication), request.visibility());
    }

    @GetMapping("/api/v1/users/{userId}/watchlist")
    @Operation(operationId = "getUserWatchlist", summary = "View another user's watchlist",
            description = "Publicly accessible. Returns 404 if the watchlist is PRIVATE and the caller "
                    + "isn't its owner - existence is hidden, not just access-denied. Passing a valid "
                    + "Bearer token for the owner also reveals a PRIVATE watchlist.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The watchlist and its items"),
            @ApiResponse(responseCode = "404", description = "No user with that id, or their watchlist is PRIVATE")
    })
    public WatchlistView getForUser(Authentication authentication,
                                    @Parameter(description = "Numeric user id") @PathVariable int userId) {
        return watchlistUseCase.getForUser(CurrentUser.idOf(authentication), userId);
    }
}
