package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.UpdatePersonRequest;
import com.ludovictemgoua.imdb.domain.model.PersonCore;

public interface PersonAdminUseCase {

    PersonCore create(CreatePersonRequest request);

    PersonCore update(String personId, UpdatePersonRequest request);

    PersonCore patch(String personId, PatchPersonRequest request);

    void delete(String personId);
}
