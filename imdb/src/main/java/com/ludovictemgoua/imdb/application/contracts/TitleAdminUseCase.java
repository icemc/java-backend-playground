package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.PrincipalRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
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
