package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.RatingView;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class TitleDetailUseCaseImpl implements TitleDetailUseCase {

    private static final int CAST_LIMIT = 20;

    private final TitleRepository titleRepository;
    private final ReviewRepository reviewRepository;

    public TitleDetailUseCaseImpl(TitleRepository titleRepository, ReviewRepository reviewRepository) {
        this.titleRepository = titleRepository;
        this.reviewRepository = reviewRepository;
    }

    @Override
    public TitleDetail getDetail(String titleId) {
        int tconst = ImdbIds.parseTitleId(titleId);
        var core = titleRepository.findCore(tconst)
                .orElseThrow(() -> new NotFoundException("No title with id " + titleId));
        var directors = titleRepository.findDirectors(tconst);
        var writers = titleRepository.findWriters(tconst);
        var cast = titleRepository.findTopCast(tconst, CAST_LIMIT);
        int castTotal = titleRepository.countCast(tconst);
        var userRating = reviewRepository.aggregateForTitle(tconst);
        return new TitleDetail(
                core.id(), core.primaryTitle(), core.originalTitle(), core.titleType(),
                core.startYear(), core.endYear(), core.runtimeMinutes(), core.genres(),
                new RatingView(core.averageRating() == null ? 0 : core.averageRating(),
                        core.numVotes() == null ? 0 : core.numVotes()),
                directors, writers, cast, castTotal,
                userRating.average(), userRating.count());
    }
}
