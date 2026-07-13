package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.ReviewRequest;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Review;

public interface ReviewUseCase {

    Review create(int userId, String titleId, ReviewRequest request);

    Review getMine(int userId, String titleId);

    Review update(int userId, String titleId, ReviewRequest request);

    void delete(int userId, String titleId, int expectedVersion);

    PagedResult<Review> listForTitle(String titleId, int page, int size);

    PagedResult<Review> listForUser(int userId, int page, int size);
}
