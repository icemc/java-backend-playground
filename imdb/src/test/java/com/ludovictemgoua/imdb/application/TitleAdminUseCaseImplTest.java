package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.rest.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.rest.PrincipalRequest;
import com.ludovictemgoua.imdb.application.rest.UpdateTitleRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TitleAdminUseCaseImplTest {

    @Mock
    TitleRepository titleRepository;

    @Test
    void createDelegatesToInsertTitle() {
        var created = new TitleCore("tt0000300", "New", "New", "movie", 2024, null, 100, List.of(), null, null, 0);
        given(titleRepository.insertTitle("New", "New", "movie", 2024, null, 100, List.of()))
                .willReturn(created);

        var result = new TitleAdminUseCaseImpl(titleRepository)
                .create(new CreateTitleRequest("New", "New", "movie", 2024, null, 100, List.of()));

        assertThat(result.id()).isEqualTo("tt0000300");
    }

    @Test
    void updateThrowsConflictOnVersionMismatch() {
        given(titleRepository.updateTitle(300, "New", "New", "movie", 2024, null, 100, List.of(), 0))
                .willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.update("tt0000300",
                new UpdateTitleRequest("New", "New", "movie", 2024, null, 100, List.of(), 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void updateThrowsNotFoundWhenTheTitleDoesNotExist() {
        given(titleRepository.updateTitle(300, "New", "New", "movie", 2024, null, 100, List.of(), 0))
                .willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.update("tt0000300",
                new UpdateTitleRequest("New", "New", "movie", 2024, null, 100, List.of(), 0)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteThrowsNotFoundWhenTheTitleDoesNotExist() {
        given(titleRepository.softDeleteTitle(300)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.delete("tt0000300")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void addPrincipalDelegatesToInsertPrincipal() {
        given(titleRepository.insertPrincipal(300, 1, "actor", null, List.of("Role"), 5))
                .willReturn(WriteResult.SUCCESS);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        useCase.addPrincipal("tt0000300", new PrincipalRequest("nm0000001", "actor", null, List.of("Role"), 5));
    }

    @Test
    void deletePrincipalThrowsNotFoundWhenMissing() {
        given(titleRepository.softDeletePrincipal(300, 5)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new TitleAdminUseCaseImpl(titleRepository);

        assertThatThrownBy(() -> useCase.deletePrincipal("tt0000300", 5)).isInstanceOf(NotFoundException.class);
    }
}
