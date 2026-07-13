package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.application.ReviewUseCaseImpl;
import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// title-detail embeds userRatingAverage/userRatingCount - any review write must evict the affected
// title's cache entry the same way an admin rating write does (CachingTitleAdminUseCase).
@Service
@Primary
public class CachingReviewUseCase implements ReviewUseCase {

    private final ReviewUseCaseImpl delegate;

    public CachingReviewUseCase(ReviewUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public Review create(int userId, String titleId, ReviewRequest request) {
        return delegate.create(userId, titleId, request);
    }

    @Override
    public Review getMine(int userId, String titleId) {
        return delegate.getMine(userId, titleId);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public Review update(int userId, String titleId, ReviewRequest request) {
        return delegate.update(userId, titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void delete(int userId, String titleId, int expectedVersion) {
        delegate.delete(userId, titleId, expectedVersion);
    }

    @Override
    public PagedResult<Review> listForTitle(String titleId, int page, int size) {
        return delegate.listForTitle(titleId, page, size);
    }

    @Override
    public PagedResult<Review> listForUser(int userId, int page, int size) {
        return delegate.listForUser(userId, page, size);
    }
}
