package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TopRatedUseCaseImplTest {

    @Mock
    TitleRepository titleRepository;

    @Test
    void fallsBackToTheConfiguredDefaultWhenMinVotesIsNotProvided() {
        var expected = List.of(new GenreTopRatedItem("tt0111161", "The Shawshank Redemption",
                1994, 9.3, 2900000, 9.2));
        given(titleRepository.findTopRated("Drama", 10, 1000)).willReturn(expected);

        var result = new TopRatedUseCaseImpl(titleRepository, 1000).findTopRated("Drama", 10, null);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void usesTheCallerSuppliedMinVotesWhenPresent() {
        var expected = List.<GenreTopRatedItem>of();
        given(titleRepository.findTopRated("Drama", 10, 50000)).willReturn(expected);

        var result = new TopRatedUseCaseImpl(titleRepository, 1000).findTopRated("Drama", 10, 50000);

        assertThat(result).isSameAs(expected);
    }
}
