package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.SharedTitle;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SixDegreesUseCaseImplTest {

    @Mock
    PersonRepository personRepository;
    @Mock
    CoStarGraphRepository graphRepository;
    @Mock
    TitleRepository titleRepository;

    // PersonResolutionUseCase is a real collaborator here, not mocked - it's an internal, pure-logic
    // helper (see its own class-level comment), not a boundary worth mocking in isolation. The real
    // boundary for this test is the repositories.
    private SixDegreesUseCaseImpl newUseCase() {
        var personResolution = new PersonResolutionUseCase(personRepository);
        return new SixDegreesUseCaseImpl(personResolution, graphRepository, personRepository, titleRepository);
    }

    @Test
    void sameResolvedPersonIsDegreeZeroWithoutQueryingTheGraph() {
        given(personRepository.findNameById(102)).willReturn(Optional.of("Kevin Bacon"));

        var outcome = newUseCase().compute("nm0000102", "nm0000102", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Found.class, found -> {
            assertThat(found.result().degree()).isZero();
            assertThat(found.result().withinRequestedMax()).isTrue();
        });
        verifyNoInteractions(graphRepository);
    }

    @Test
    void ambiguousNameOnFirstSideShortCircuitsBeforeResolvingSecond() {
        var candidates = List.of(
                new PersonCandidate("nm0000020", "Jamie Lee", 1975, List.of()),
                new PersonCandidate("nm0000021", "Jamie Lee", 1990, List.of()));
        given(personRepository.findByName("Jamie Lee")).willReturn(candidates);

        var outcome = newUseCase().compute("Jamie Lee", "nm0000158", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Ambiguous.class,
                amb -> assertThat(amb.candidates()).hasSize(2));
        verifyNoInteractions(graphRepository);
        verify(personRepository, never()).findNameById(anyInt());
    }

    @Test
    void personNotFoundOnFirstSideShortCircuits() {
        given(personRepository.findByName("Nobody")).willReturn(List.of());

        var outcome = newUseCase().compute("Nobody", "nm0000158", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.PersonNotFound.class,
                notFound -> assertThat(notFound.query()).isEqualTo("Nobody"));
        verifyNoInteractions(graphRepository);
    }

    @Test
    void resolvesFullAnnotatedPathWhenDegreeIsWithinRequestedMax() {
        given(personRepository.findNameById(102)).willReturn(Optional.of("Kevin Bacon"));
        given(personRepository.findNameById(129)).willReturn(Optional.of("Tom Cruise"));
        given(graphRepository.findShortestPath(102, 129))
                .willReturn(Optional.of(new GraphPath(1, List.of(102, 129))));
        given(personRepository.findNamesByIds(List.of(102, 129)))
                .willReturn(Map.of(102, "Kevin Bacon", 129, "Tom Cruise"));
        given(titleRepository.findAnyCommonTitle(102, 129))
                .willReturn(Optional.of(new SharedTitle("tt0100405", "A Few Good Men")));

        var outcome = newUseCase().compute("nm0000102", "nm0000129", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Found.class, found -> {
            var result = found.result();
            assertThat(result.degree()).isEqualTo(1);
            assertThat(result.withinRequestedMax()).isTrue();
            assertThat(result.path()).hasSize(2);
            assertThat(result.path().get(1).sharedTitle().primaryTitle()).isEqualTo("A Few Good Men");
        });
    }

    @Test
    void reportsTrueDegreeButNoPathWhenBeyondRequestedMax() {
        given(personRepository.findNameById(102)).willReturn(Optional.of("Kevin Bacon"));
        given(personRepository.findNameById(158)).willReturn(Optional.of("Tom Hanks"));
        given(graphRepository.findShortestPath(102, 158))
                .willReturn(Optional.of(new GraphPath(5, List.of(102, 2, 3, 4, 5, 158))));

        var outcome = newUseCase().compute("nm0000102", "nm0000158", 3);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Found.class, found -> {
            var result = found.result();
            assertThat(result.degree()).isEqualTo(5);
            assertThat(result.withinRequestedMax()).isFalse();
            assertThat(result.path()).isEmpty();
        });
        verifyNoInteractions(titleRepository);
    }

    @Test
    void reportsNullDegreeWhenNoConnectionExistsWithinTheAbsoluteCap() {
        given(personRepository.findNameById(102)).willReturn(Optional.of("Kevin Bacon"));
        given(personRepository.findNameById(999)).willReturn(Optional.of("Nobody Connected"));
        given(graphRepository.findShortestPath(102, 999)).willReturn(Optional.empty());

        var outcome = newUseCase().compute("nm0000102", "nm0000999", 7);

        assertThat(outcome).isInstanceOfSatisfying(SixDegreesOutcome.Found.class, found -> {
            assertThat(found.result().degree()).isNull();
            assertThat(found.result().withinRequestedMax()).isFalse();
            assertThat(found.result().path()).isEmpty();
        });
    }
}
