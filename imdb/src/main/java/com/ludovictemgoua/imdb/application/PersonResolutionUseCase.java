package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.domain.model.PersonCandidate;
import com.ludovictemgoua.imdb.domain.model.PersonResolution;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.List;

// Not an interface: it's an internal collaborator used only by SixDegreesUseCaseImpl, not a seam any
// controller or infrastructure decorator needs to substitute. Interfaces earn their keep at the
// boundaries that actually get swapped or mocked in isolation (repositories, the top-level use cases
// below) - not by default on every class.
@Service
class PersonResolutionUseCase {

    private final PersonRepository personRepository;

    PersonResolutionUseCase(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    PersonResolution resolve(String query) {
        if (query.startsWith("nm")) {
            int nconst = ImdbIds.parsePersonId(query);
            return personRepository.findNameById(nconst)
                    .<PersonResolution>map(name -> new PersonResolution.Resolved(nconst, name))
                    .orElseGet(PersonResolution.NotFound::new);
        }
        List<PersonCandidate> candidates = personRepository.findByName(query);
        return switch (candidates.size()) {
            case 0 -> new PersonResolution.NotFound();
            case 1 -> {
                PersonCandidate only = candidates.get(0);
                yield new PersonResolution.Resolved(ImdbIds.parsePersonId(only.id()), only.name());
            }
            default -> new PersonResolution.Ambiguous(candidates);
        };
    }
}
