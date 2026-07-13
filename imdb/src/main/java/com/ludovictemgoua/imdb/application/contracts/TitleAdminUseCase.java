package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.domain.model.TitleCore;

public interface TitleAdminUseCase {

    TitleCore create(CreateTitleRequest request);

    TitleCore update(String titleId, UpdateTitleRequest request);

    TitleCore patch(String titleId, PatchTitleRequest request);

    void delete(String titleId);

    void upsertCrew(String titleId, CrewRequest request);

    void upsertRating(String titleId, RatingRequest request);

    void deleteRating(String titleId);
}
