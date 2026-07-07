package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.PersonResolution;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class PersonResolutionUseCaseTest {

    @Mock
    PersonRepository personRepository;

    @Test
    void resolvesDirectlyByIdWithoutSearchingByName() {
        given(personRepository.findNameById(102)).willReturn(Optional.of("Kevin Bacon"));

        var resolution = new PersonResolutionUseCase(personRepository).resolve("nm0000102");

        assertThat(resolution).isInstanceOfSatisfying(PersonResolution.Resolved.class, resolved -> {
            assertThat(resolved.nconst()).isEqualTo(102);
            assertThat(resolved.name()).isEqualTo("Kevin Bacon");
        });
    }

    @Test
    void unknownIdIsNotFound() {
        given(personRepository.findNameById(999)).willReturn(Optional.empty());

        var resolution = new PersonResolutionUseCase(personRepository).resolve("nm0000999");

        assertThat(resolution).isInstanceOf(PersonResolution.NotFound.class);
    }

    @Test
    void singleNameMatchResolves() {
        given(personRepository.findByName("Kevin Bacon"))
                .willReturn(List.of(new PersonCandidate("nm0000102", "Kevin Bacon", 1958, List.of())));

        var resolution = new PersonResolutionUseCase(personRepository).resolve("Kevin Bacon");

        assertThat(resolution).isInstanceOfSatisfying(PersonResolution.Resolved.class,
                resolved -> assertThat(resolved.nconst()).isEqualTo(102));
    }

    @Test
    void multipleNameMatchesAreAmbiguous() {
        given(personRepository.findByName("Jamie Lee")).willReturn(List.of(
                new PersonCandidate("nm0000020", "Jamie Lee", 1975, List.of()),
                new PersonCandidate("nm0000021", "Jamie Lee", 1990, List.of())));

        var resolution = new PersonResolutionUseCase(personRepository).resolve("Jamie Lee");

        assertThat(resolution).isInstanceOfSatisfying(PersonResolution.Ambiguous.class,
                amb -> assertThat(amb.candidates()).hasSize(2));
    }

    @Test
    void noNameMatchesIsNotFound() {
        given(personRepository.findByName("Nobody")).willReturn(List.of());

        var resolution = new PersonResolutionUseCase(personRepository).resolve("Nobody");

        assertThat(resolution).isInstanceOf(PersonResolution.NotFound.class);
    }
}
