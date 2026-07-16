package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.rest.ReviewRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.model.Review;
import com.ludovictemgoua.imdb.domain.repository.ReviewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ReviewUseCaseImplTest {

    @Mock
    ReviewRepository reviewRepository;

    @Test
    void createThrowsConflictWhenAReviewAlreadyExists() {
        given(reviewRepository.findByUserAndTitle(7, 100)).willReturn(
                Optional.of(new Review(1, 7, 100, 8, "Existing", 0, Instant.now(), Instant.now())));
        var useCase = new ReviewUseCaseImpl(reviewRepository);

        assertThatThrownBy(() -> useCase.create(7, "tt0000100", new ReviewRequest(9, "New", 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void createInsertsWhenNoneExistsYet() {
        given(reviewRepository.findByUserAndTitle(7, 100)).willReturn(Optional.empty());
        var created = new Review(1, 7, 100, 9, "New", 0, Instant.now(), Instant.now());
        given(reviewRepository.insert(7, 100, 9, "New")).willReturn(created);

        var result = new ReviewUseCaseImpl(reviewRepository).create(7, "tt0000100", new ReviewRequest(9, "New", 0));

        assertThat(result.rating()).isEqualTo(9);
    }
}
