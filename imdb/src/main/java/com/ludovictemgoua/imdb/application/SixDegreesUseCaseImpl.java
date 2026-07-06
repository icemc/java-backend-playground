package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.model.PersonResolution;
import com.ludovictemgoua.imdb.domain.model.SharedTitle;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SixDegreesUseCaseImpl implements SixDegreesUseCase {

    private final PersonResolutionUseCase personResolution;
    private final CoStarGraphRepository graphRepository;
    private final PersonRepository personRepository;
    private final TitleRepository titleRepository;

    public SixDegreesUseCaseImpl(PersonResolutionUseCase personResolution, CoStarGraphRepository graphRepository,
                                  PersonRepository personRepository, TitleRepository titleRepository) {
        this.personResolution = personResolution;
        this.graphRepository = graphRepository;
        this.personRepository = personRepository;
        this.titleRepository = titleRepository;
    }

    @Override
    public SixDegreesOutcome compute(String queryA, String queryB, int maxDegree) {
        PersonResolution resolvedA = personResolution.resolve(queryA);
        if (resolvedA instanceof PersonResolution.Ambiguous a) {
            return new SixDegreesOutcome.Ambiguous(queryA, a.candidates());
        }
        if (resolvedA instanceof PersonResolution.NotFound) {
            return new SixDegreesOutcome.PersonNotFound(queryA);
        }
        PersonResolution resolvedB = personResolution.resolve(queryB);
        if (resolvedB instanceof PersonResolution.Ambiguous b) {
            return new SixDegreesOutcome.Ambiguous(queryB, b.candidates());
        }
        if (resolvedB instanceof PersonResolution.NotFound) {
            return new SixDegreesOutcome.PersonNotFound(queryB);
        }

        var a = (PersonResolution.Resolved) resolvedA;
        var b = (PersonResolution.Resolved) resolvedB;
        PersonRef personA = new PersonRef(ImdbIds.formatPersonId(a.nconst()), a.name());
        PersonRef personB = new PersonRef(ImdbIds.formatPersonId(b.nconst()), b.name());

        if (a.nconst() == b.nconst()) {
            PathStep onlyStep = new PathStep(personA.id(), personA.name(), null);
            return new SixDegreesOutcome.Found(
                    new SixDegreesResult(personA, personB, 0, true, List.of(onlyStep)));
        }

        Optional<GraphPath> match = graphRepository.findShortestPath(a.nconst(), b.nconst());
        if (match.isEmpty()) {
            return new SixDegreesOutcome.Found(
                    new SixDegreesResult(personA, personB, null, false, List.of()));
        }

        GraphPath path = match.get();
        boolean withinMax = path.degree() <= maxDegree;
        List<PathStep> steps = withinMax ? buildPath(path.personIds()) : List.of();
        return new SixDegreesOutcome.Found(
                new SixDegreesResult(personA, personB, path.degree(), withinMax, steps));
    }

    private List<PathStep> buildPath(List<Integer> nconsts) {
        Map<Integer, String> names = personRepository.findNamesByIds(nconsts);
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < nconsts.size(); i++) {
            int nconst = nconsts.get(i);
            SharedTitle sharedTitle = i == 0 ? null
                    : titleRepository.findAnyCommonTitle(nconsts.get(i - 1), nconst).orElse(null);
            steps.add(new PathStep(ImdbIds.formatPersonId(nconst), names.get(nconst), sharedTitle));
        }
        return steps;
    }
}
