package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
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
class PersonAdminUseCaseImplTest {

    @Mock
    PersonRepository personRepository;

    @Test
    void createDelegatesToInsertPerson() {
        var created = new PersonCore("nm0000011", "New Person", null, null, List.of(), 0);
        given(personRepository.insertPerson("New Person", null, null, List.of())).willReturn(created);

        var result = new PersonAdminUseCaseImpl(personRepository)
                .create(new CreatePersonRequest("New Person", null, null, List.of()));

        assertThat(result.id()).isEqualTo("nm0000011");
    }

    @Test
    void updateThrowsConflictOnVersionMismatch() {
        given(personRepository.updatePerson(11, "New", null, null, List.of(), 0))
                .willReturn(WriteResult.VERSION_CONFLICT);
        var useCase = new PersonAdminUseCaseImpl(personRepository);

        assertThatThrownBy(() -> useCase.update("nm0000011",
                new UpdatePersonRequest("New", null, null, List.of(), 0)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void deleteThrowsNotFoundWhenThePersonDoesNotExist() {
        given(personRepository.softDeletePerson(11)).willReturn(WriteResult.NOT_FOUND);
        var useCase = new PersonAdminUseCaseImpl(personRepository);

        assertThatThrownBy(() -> useCase.delete("nm0000011")).isInstanceOf(NotFoundException.class);
    }
}
