package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.CastMember;
import com.ludovictemgoua.imdb.domain.model.CreditedPerson;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
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
class TitleDetailUseCaseImplTest {

    @Mock
    TitleRepository titleRepository;

    @Test
    void assemblesTitleDetailFromFiveRepositoryCalls() {
        var core = new TitleCore("tt0111161", "The Shawshank Redemption", "The Shawshank Redemption",
                "movie", 1994, null, 142, List.of("Drama"), 9.3, 2900000);
        given(titleRepository.findCore(111161)).willReturn(Optional.of(core));
        given(titleRepository.findDirectors(111161))
                .willReturn(List.of(new CreditedPerson("nm0001104", "Frank Darabont")));
        given(titleRepository.findWriters(111161))
                .willReturn(List.of(new CreditedPerson("nm0001104", "Frank Darabont")));
        given(titleRepository.findTopCast(111161, 20))
                .willReturn(List.of(new CastMember("nm0000209", "Tim Robbins", "actor",
                        List.of("Andy Dufresne"), 1)));
        given(titleRepository.countCast(111161)).willReturn(20);

        var detail = new TitleDetailUseCaseImpl(titleRepository).getDetail("tt0111161");

        assertThat(detail.id()).isEqualTo("tt0111161");
        assertThat(detail.rating().average()).isEqualTo(9.3);
        assertThat(detail.rating().numVotes()).isEqualTo(2900000);
        assertThat(detail.directors()).hasSize(1);
        assertThat(detail.cast()).hasSize(1);
        assertThat(detail.castTotalCount()).isEqualTo(20);
    }

    @Test
    void missingCoreThrowsNotFound() {
        given(titleRepository.findCore(111161)).willReturn(Optional.empty());

        assertThatThrownBy(() -> new TitleDetailUseCaseImpl(titleRepository).getDetail("tt0111161"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void nullRatingFieldsDefaultToZeroRatherThanNull() {
        var core = new TitleCore("tt9999999", "Unrated Title", "Unrated Title",
                "movie", null, null, null, List.of(), null, null);
        given(titleRepository.findCore(9999999)).willReturn(Optional.of(core));
        given(titleRepository.findDirectors(9999999)).willReturn(List.of());
        given(titleRepository.findWriters(9999999)).willReturn(List.of());
        given(titleRepository.findTopCast(9999999, 20)).willReturn(List.of());
        given(titleRepository.countCast(9999999)).willReturn(0);

        var detail = new TitleDetailUseCaseImpl(titleRepository).getDetail("tt9999999");

        assertThat(detail.rating().average()).isZero();
        assertThat(detail.rating().numVotes()).isZero();
    }
}
