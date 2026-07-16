package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.rest.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.rest.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.PersonAdminUseCaseImpl;
import com.ludovictemgoua.imdb.application.rest.UpdatePersonRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// A renamed/removed person can change six-degrees path enrichment or its underlying graph edges -
// coarse full-region eviction on any update/delete, same trade-off as CachingTitleAdminUseCase's
// principal writes. create() needs no eviction: a brand-new person can't already be in any cached
// six-degrees result.
@Service
@Primary
public class CachingPersonAdminUseCase implements PersonAdminUseCase {

    private final PersonAdminUseCaseImpl delegate;

    public CachingPersonAdminUseCase(PersonAdminUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public PersonCore create(CreatePersonRequest request) {
        return delegate.create(request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public PersonCore update(String personId, UpdatePersonRequest request) {
        return delegate.update(personId, request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public PersonCore patch(String personId, PatchPersonRequest request) {
        return delegate.patch(personId, request);
    }

    @Override
    @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    public void delete(String personId) {
        delegate.delete(personId);
    }
}
