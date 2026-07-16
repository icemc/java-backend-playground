package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.rest.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.rest.CrewRequest;
import com.ludovictemgoua.imdb.application.rest.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.rest.PrincipalRequest;
import com.ludovictemgoua.imdb.application.rest.RatingRequest;
import com.ludovictemgoua.imdb.application.rest.UpdateTitleRequest;
import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;
import com.ludovictemgoua.imdb.domain.model.TitleCore;

import java.util.List;

public interface TitleAdminUseCase {

    TitleCore create(CreateTitleRequest request);

    TitleCore update(String titleId, UpdateTitleRequest request);

    TitleCore patch(String titleId, PatchTitleRequest request);

    void delete(String titleId);

    void upsertCrew(String titleId, CrewRequest request);

    void upsertRating(String titleId, RatingRequest request);

    void deleteRating(String titleId);

    List<PrincipalCredit> getAllPrincipals(String titleId);

    void addPrincipal(String titleId, PrincipalRequest request);

    void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion);

    void deletePrincipal(String titleId, int ordering);
}
