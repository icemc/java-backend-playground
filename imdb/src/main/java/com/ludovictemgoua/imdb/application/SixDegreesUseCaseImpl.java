package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.application.rest.PathStep;
import com.ludovictemgoua.imdb.application.rest.PersonRef;
import com.ludovictemgoua.imdb.application.rest.SixDegreesResult;
import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.model.PersonResolution;
import com.ludovictemgoua.imdb.domain.model.SharedTitle;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SixDegreesUseCaseImpl implements SixDegreesUseCase {

    private static final Logger log = LoggerFactory.getLogger(SixDegreesUseCaseImpl.class);

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
            log.info("person resolution ambiguous: query={} candidateCount={}", queryA, a.candidates().size());
            return new SixDegreesOutcome.Ambiguous(queryA, a.candidates());
        }
        if (resolvedA instanceof PersonResolution.NotFound) {
            log.info("person resolution not found: query={}", queryA);
            return new SixDegreesOutcome.PersonNotFound(queryA);
        }
        PersonResolution resolvedB = personResolution.resolve(queryB);
        if (resolvedB instanceof PersonResolution.Ambiguous b) {
            log.info("person resolution ambiguous: query={} candidateCount={}", queryB, b.candidates().size());
            return new SixDegreesOutcome.Ambiguous(queryB, b.candidates());
        }
        if (resolvedB instanceof PersonResolution.NotFound) {
            log.info("person resolution not found: query={}", queryB);
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

        // Timed explicitly (not just left to the request-level duration in RequestLoggingFilter) -
        // this is the one query in the whole app whose cost varies by graph shape rather than being
        // a bounded index lookup (LLD §5), so its own duration is worth a dedicated log line to spot
        // a slow pair without having to cross-reference the trace.
        long startMillis = System.currentTimeMillis();
        Optional<GraphPath> match = graphRepository.findShortestPath(a.nconst(), b.nconst());
        long durationMs = System.currentTimeMillis() - startMillis;

        if (match.isEmpty()) {
            log.info("six degrees not found: personA={} personB={} durationMs={}",
                    personA.id(), personB.id(), durationMs);
            return new SixDegreesOutcome.Found(
                    new SixDegreesResult(personA, personB, null, false, List.of()));
        }

        GraphPath path = match.get();
        boolean withinMax = path.degree() <= maxDegree;
        log.info("six degrees computed: personA={} personB={} degree={} withinMax={} durationMs={}",
                personA.id(), personB.id(), path.degree(), withinMax, durationMs);
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
