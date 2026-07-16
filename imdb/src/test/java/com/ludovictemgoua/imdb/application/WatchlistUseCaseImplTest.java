package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import com.ludovictemgoua.imdb.domain.repository.WatchlistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WatchlistUseCaseImplTest {

    @Mock
    WatchlistRepository watchlistRepository;

    @Test
    void getOwnDelegatesToFindOrCreate() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findOrCreateByUserId(7)).willReturn(view);

        assertThat(new WatchlistUseCaseImpl(watchlistRepository).getOwn(7)).isSameAs(view);
    }

    @Test
    void getForUserReturnsThePublicWatchlistToAnyone() {
        var view = new WatchlistView(1, 7, Visibility.PUBLIC, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));

        var result = new WatchlistUseCaseImpl(watchlistRepository).getForUser(Optional.empty(), 7);

        assertThat(result).isSameAs(view);
    }

    @Test
    void getForUserThrowsNotFoundForAPrivateWatchlistViewedByAStranger() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));
        var useCase = new WatchlistUseCaseImpl(watchlistRepository);

        assertThatThrownBy(() -> useCase.getForUser(Optional.of(99), 7))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getForUserAllowsTheOwnerToViewTheirOwnPrivateWatchlist() {
        var view = new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of());
        given(watchlistRepository.findByUserId(7)).willReturn(Optional.of(view));

        var result = new WatchlistUseCaseImpl(watchlistRepository).getForUser(Optional.of(7), 7);

        assertThat(result).isSameAs(view);
    }
}
