package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
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

@RestController
@Validated
public class ReviewController {

    private final ReviewUseCase reviewUseCase;

    public ReviewController(ReviewUseCase reviewUseCase) {
        this.reviewUseCase = reviewUseCase;
    }

    @PostMapping("/api/v1/titles/{titleId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public Review create(Authentication authentication, @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.create(CurrentUser.requireId(authentication), titleId, request);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews")
    public PagedResult<Review> listForTitle(
            @PathVariable String titleId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForTitle(titleId, page, size);
    }

    @GetMapping("/api/v1/titles/{titleId}/reviews/me")
    public Review getMine(Authentication authentication, @PathVariable String titleId) {
        return reviewUseCase.getMine(CurrentUser.requireId(authentication), titleId);
    }

    @PutMapping("/api/v1/titles/{titleId}/reviews/me")
    public Review update(Authentication authentication, @PathVariable String titleId,
                         @Valid @RequestBody ReviewRequest request) {
        return reviewUseCase.update(CurrentUser.requireId(authentication), titleId, request);
    }

    @DeleteMapping("/api/v1/titles/{titleId}/reviews/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable String titleId, @RequestParam int expectedVersion) {
        reviewUseCase.delete(CurrentUser.requireId(authentication), titleId, expectedVersion);
    }

    @GetMapping("/api/v1/users/{userId}/reviews")
    public PagedResult<Review> listForUser(
            @PathVariable int userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewUseCase.listForUser(userId, page, size);
    }
}
