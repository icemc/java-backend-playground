package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
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
class ListUseCaseImplTest {

    @Mock
    CustomListRepository customListRepository;

    @Test
    void getByIdReturnsAPublicListToAnyone() {
        var view = new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));

        assertThat(new ListUseCaseImpl(customListRepository).getById(1, Optional.empty())).isSameAs(view);
    }

    @Test
    void getByIdThrowsNotFoundForAPrivateListViewedByAStranger() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.getById(1, Optional.of(99))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addItemThrowsForbiddenWhenAStrangerWritesToAPublicList() {
        var view = new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.addItem(1, 99, "tt0000100")).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void addItemThrowsNotFoundWhenAStrangerWritesToAPrivateList() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));
        var useCase = new ListUseCaseImpl(customListRepository);

        assertThatThrownBy(() -> useCase.addItem(1, 99, "tt0000100")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addItemSucceedsForTheOwner() {
        var view = new CustomListView(1, 7, "Private", Visibility.PRIVATE, 0, List.of());
        given(customListRepository.findById(1)).willReturn(Optional.of(view));

        new ListUseCaseImpl(customListRepository).addItem(1, 7, "tt0000100");
    }
}
