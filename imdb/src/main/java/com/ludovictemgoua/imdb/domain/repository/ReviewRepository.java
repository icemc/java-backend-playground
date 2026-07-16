package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.RatingAggregate;
import com.ludovictemgoua.imdb.domain.model.Review;

import java.util.Optional;

public interface ReviewRepository {

    Review insert(int userId, int titleId, int rating, String body);

    Optional<Review> findByUserAndTitle(int userId, int titleId);

    WriteResult update(int reviewId, int rating, String body, int expectedVersion);

    WriteResult softDelete(int reviewId, int expectedVersion);

    PagedResult<Review> findByTitle(int titleId, int page, int size);

    PagedResult<Review> findByUser(int userId, int page, int size);

    RatingAggregate aggregateForTitle(int titleId);
}
