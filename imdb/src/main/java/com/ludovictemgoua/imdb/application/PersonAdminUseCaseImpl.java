package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.rest.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.rest.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.rest.UpdatePersonRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import com.ludovictemgoua.imdb.domain.repository.PersonRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

@Service
public class PersonAdminUseCaseImpl implements PersonAdminUseCase {

    private final PersonRepository personRepository;

    public PersonAdminUseCaseImpl(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @Override
    public PersonCore create(CreatePersonRequest request) {
        return personRepository.insertPerson(
                request.primaryName(), request.birthYear(), request.deathYear(), request.primaryProfession());
    }

    @Override
    public PersonCore update(String personId, UpdatePersonRequest request) {
        int nconst = ImdbIds.parsePersonId(personId);
        handle(personRepository.updatePerson(nconst, request.primaryName(), request.birthYear(),
                request.deathYear(), request.primaryProfession(), request.version()), personId);
        return personRepository.findCore(nconst).orElseThrow();
    }

    @Override
    public PersonCore patch(String personId, PatchPersonRequest request) {
        int nconst = ImdbIds.parsePersonId(personId);
        PersonCore current = personRepository.findCore(nconst)
                .orElseThrow(() -> new NotFoundException("No person with id " + personId));
        handle(personRepository.updatePerson(nconst,
                request.primaryName() != null ? request.primaryName() : current.primaryName(),
                request.birthYear() != null ? request.birthYear() : current.birthYear(),
                request.deathYear() != null ? request.deathYear() : current.deathYear(),
                request.primaryProfession() != null ? request.primaryProfession() : current.primaryProfession(),
                request.version()), personId);
        return personRepository.findCore(nconst).orElseThrow();
    }

    @Override
    public void delete(String personId) {
        handle(personRepository.softDeletePerson(ImdbIds.parsePersonId(personId)), personId);
    }

    private static void handle(WriteResult result, String personId) {
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No person with id " + personId);
            case VERSION_CONFLICT -> throw new ConflictException(
                    "Person " + personId + " was modified by someone else - refresh and retry");
            case SUCCESS -> { }
        }
    }
}
