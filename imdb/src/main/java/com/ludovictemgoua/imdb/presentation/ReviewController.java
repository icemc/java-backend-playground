package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.rest.ReviewRequest;
import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@Validated
@Tag(name = "Reviews", description = "One rating-and-text review per user per title")
public class ReviewController {

    private final ReviewUseCase reviewUseCase;

    public ReviewController(ReviewUseCase reviewUseCase) {
        this.reviewUseCase = reviewUseCase;
    }

    @PostMapping("/api/v1/titles/{titleId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "createReview", summary = "Post a review for a title",
            description = "One review per (user, title) - fails with 409 if the caller already reviewed "
                    + "this title (use updateMyReviewForTitle instead).")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Review created"),
            @ApiResponse(responseCode = "400", description = "Request failed validation (rating out of 1-10)"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "409", description = "Caller already reviewed this title")
    })
    public Review create(Authentication authentication,
                         @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.create(CurrentUser.requireId(authentication), titleId, request);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews")
    @Operation(operationId = "listReviewsForTitle", summary = "List all reviews for a title",
            description = "Publicly accessible; paged, newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of reviews"),
            @ApiResponse(responseCode = "400", description = "titleId is not a valid tt-prefixed id, or page/size is out of range")
    })
    public PagedResult<Review> listForTitle(
            @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForTitle(titleId, page, size);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews/me")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "getMyReviewForTitle", summary = "Get the authenticated user's own review for a title",
            description = "Returns 404 if the caller hasn't reviewed this title yet.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's review"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "404", description = "Caller has not reviewed this title")
    })
    public Review getMine(Authentication authentication,
                          @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        return reviewUseCase.getMine(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/titles/{titleId}/reviews/me")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateMyReviewForTitle", summary = "Update the authenticated user's review for a title",
            description = "Optimistic locking - the request's version field must match the row's current "
                    + "version or the update is rejected with 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Review updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "404", description = "Caller has not reviewed this title"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public Review update(Authentication authentication,
                         @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.update(CurrentUser.requireId(authentication), titleId, request);
    }

    @DeleteMapping("/api/v1/titles/{titleId}/reviews/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteMyReviewForTitle", summary = "Delete the authenticated user's review for a title",
            description = "Optimistic locking via the expectedVersion query parameter.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Review deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "404", description = "Caller has not reviewed this title"),
            @ApiResponse(responseCode = "409", description = "expectedVersion does not match the current row - refresh and retry")
    })
    public void delete(Authentication authentication,
                       @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                       @Parameter(description = "Version read from the review being deleted, for optimistic locking")
                       @RequestParam int expectedVersion) {
        reviewUseCase.delete(CurrentUser.requireId(authentication), titleId, expectedVersion);
    }

    @GetMapping("/api/v1/users/{userId}/reviews")
    @Operation(operationId = "listReviewsByUser", summary = "List every review a user has written",
            description = "Publicly accessible; paged, newest first.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of reviews"),
            @ApiResponse(responseCode = "400", description = "page/size is out of range")
    })
    public PagedResult<Review> listForUser(
            @Parameter(description = "Numeric user id") @PathVariable int userId,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForUser(userId, page, size);
    }
}
