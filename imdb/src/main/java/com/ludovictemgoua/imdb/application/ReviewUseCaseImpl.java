package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.application.rest.ReviewRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class ReviewUseCaseImpl implements ReviewUseCase {

    private final ReviewRepository reviewRepository;

    public ReviewUseCaseImpl(ReviewRepository reviewRepository) {
        this.reviewRepository = reviewRepository;
    }

    @Override
    public Review create(int userId, String titleId, ReviewRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        if (reviewRepository.findByUserAndTitle(userId, tconst).isPresent()) {
            throw new ConflictException("You already reviewed this title - use PUT to update it");
        }
        return reviewRepository.insert(userId, tconst, request.rating(), request.body());
    }

    @Override
    public Review getMine(int userId, String titleId) {
        return reviewRepository.findByUserAndTitle(userId, ImdbIds.parseTitleId(titleId))
                .orElseThrow(() -> new NotFoundException("You haven't reviewed this title"));
    }

    @Override
    public Review update(int userId, String titleId, ReviewRequest request) {
        Review existing = getMine(userId, titleId);
        WriteResult result = reviewRepository.update(existing.id(), request.rating(), request.body(), request.version());
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Your review was modified concurrently - refresh and retry");
        }
        return getMine(userId, titleId);
    }

    @Override
    public void delete(int userId, String titleId, int expectedVersion) {
        Review existing = getMine(userId, titleId);
        WriteResult result = reviewRepository.softDelete(existing.id(), expectedVersion);
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("Your review was modified concurrently - refresh and retry");
        }
    }

    @Override
    public PagedResult<Review> listForTitle(String titleId, int page, int size) {
        return reviewRepository.findByTitle(ImdbIds.parseTitleId(titleId), page, size);
    }

    @Override
    public PagedResult<Review> listForUser(int userId, int page, int size) {
        return reviewRepository.findByUser(userId, page, size);
    }
}
