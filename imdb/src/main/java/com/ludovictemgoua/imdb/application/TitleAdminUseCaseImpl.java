package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TitleAdminUseCaseImpl implements TitleAdminUseCase {

    private final TitleRepository titleRepository;

    public TitleAdminUseCaseImpl(TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    @Override
    public TitleCore create(CreateTitleRequest request) {
        return titleRepository.insertTitle(request.primaryTitle(), request.originalTitle(), request.titleType(),
                request.startYear(), request.endYear(), request.runtimeMinutes(), request.genres());
    }

    @Override
    public TitleCore update(String titleId, UpdateTitleRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.updateTitle(tconst, request.primaryTitle(), request.originalTitle(),
                request.titleType(), request.startYear(), request.endYear(), request.runtimeMinutes(),
                request.genres(), request.version()), titleId);
        return titleRepository.findCore(tconst).orElseThrow();
    }

    @Override
    public TitleCore patch(String titleId, PatchTitleRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        TitleCore current = titleRepository.findCore(tconst)
                .orElseThrow(() -> new NotFoundException("No title with id " + titleId));
        handle(titleRepository.updateTitle(tconst,
                request.primaryTitle() != null ? request.primaryTitle() : current.primaryTitle(),
                request.originalTitle() != null ? request.originalTitle() : current.originalTitle(),
                request.titleType() != null ? request.titleType() : current.titleType(),
                request.startYear() != null ? request.startYear() : current.startYear(),
                request.endYear() != null ? request.endYear() : current.endYear(),
                request.runtimeMinutes() != null ? request.runtimeMinutes() : current.runtimeMinutes(),
                request.genres() != null ? request.genres() : current.genres(),
                request.version()), titleId);
        return titleRepository.findCore(tconst).orElseThrow();
    }

    @Override
    public void delete(String titleId) {
        handle(titleRepository.softDeleteTitle(ImdbIds.parseTitleId(titleId)), titleId);
    }

    @Override
    public void upsertCrew(String titleId, CrewRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        List<Integer> directorIds = toPersonIds(request.directors());
        List<Integer> writerIds = toPersonIds(request.writers());
        handle(titleRepository.upsertCrew(tconst, directorIds, writerIds), titleId);
    }

    @Override
    public void upsertRating(String titleId, RatingRequest request) {
        handle(titleRepository.upsertRating(ImdbIds.parseTitleId(titleId),
                request.averageRating(), request.numVotes()), titleId);
    }

    @Override
    public void deleteRating(String titleId) {
        handle(titleRepository.deleteRating(ImdbIds.parseTitleId(titleId)), titleId);
    }

    @Override
    public List<PrincipalCredit> getAllPrincipals(String titleId) {
        return titleRepository.findAllPrincipals(ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void addPrincipal(String titleId, PrincipalRequest request) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.insertPrincipal(tconst, ImdbIds.parsePersonId(request.personId()),
                request.category(), request.job(), request.characters(), request.ordering()), titleId);
    }

    @Override
    public void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion) {
        int tconst = ImdbIds.parseTitleId(titleId);
        handle(titleRepository.updatePrincipal(tconst, ordering, request.category(), request.job(),
                request.characters(), expectedVersion), titleId);
    }

    @Override
    public void deletePrincipal(String titleId, int ordering) {
        handle(titleRepository.softDeletePrincipal(ImdbIds.parseTitleId(titleId), ordering), titleId);
    }

    private static List<Integer> toPersonIds(List<String> personIds) {
        return personIds == null ? List.of()
                : personIds.stream().map(ImdbIds::parsePersonId).collect(Collectors.toList());
    }

    private static void handle(WriteResult result, String titleId) {
        switch (result) {
            case NOT_FOUND -> throw new NotFoundException("No title with id " + titleId);
            case VERSION_CONFLICT -> throw new ConflictException(
                    "Title " + titleId + " was modified by someone else - refresh and retry");
            case SUCCESS -> { }
        }
    }
}
