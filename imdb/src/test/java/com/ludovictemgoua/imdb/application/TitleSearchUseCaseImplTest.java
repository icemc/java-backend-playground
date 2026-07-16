package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TitleSearchUseCaseImplTest {

    @Mock
    TitleRepository titleRepository;

    @Test
    void delegatesSearchToTheRepositoryUnchanged() {
        var expected = new PagedResult<>(
                List.of(new TitleSummary("tt0111161", "The Shawshank Redemption",
                        "The Shawshank Redemption", "movie", 1994, null)),
                1, 0, 20);
        given(titleRepository.search("shawshank", 0, 20)).willReturn(expected);

        var result = new TitleSearchUseCaseImpl(titleRepository).search("shawshank", 0, 20);

        assertThat(result).isSameAs(expected);
    }
}
