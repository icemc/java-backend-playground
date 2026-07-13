package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.PrincipalRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.TitleAdminUseCaseImpl;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

// Precise title-detail eviction on any write affecting that title; a rating write also clears the
// entire top-rated region (allEntries) since there's no cheap way to know which genre/limit/minVotes
// combinations it affects - the same coarse-but-correct trade-off documented in
// docs/crud-expansion-design.md §6.2. Admin writes are expected to be infrequent, so full-region
// eviction here is cheap in practice.
@Service
@Primary
public class CachingTitleAdminUseCase implements TitleAdminUseCase {

    private final TitleAdminUseCaseImpl delegate;

    public CachingTitleAdminUseCase(TitleAdminUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    public TitleCore create(CreateTitleRequest request) {
        return delegate.create(request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public TitleCore update(String titleId, UpdateTitleRequest request) {
        return delegate.update(titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public TitleCore patch(String titleId, PatchTitleRequest request) {
        return delegate.patch(titleId, request);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void delete(String titleId) {
        delegate.delete(titleId);
    }

    @Override
    @CacheEvict(cacheNames = "title-detail", key = "#titleId")
    public void upsertCrew(String titleId, CrewRequest request) {
        delegate.upsertCrew(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "top-rated", allEntries = true)
    })
    public void upsertRating(String titleId, RatingRequest request) {
        delegate.upsertRating(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "top-rated", allEntries = true)
    })
    public void deleteRating(String titleId) {
        delegate.deleteRating(titleId);
    }

    @Override
    public List<PrincipalCredit> getAllPrincipals(String titleId) {
        return delegate.getAllPrincipals(titleId);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void addPrincipal(String titleId, PrincipalRequest request) {
        delegate.addPrincipal(titleId, request);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void updatePrincipal(String titleId, int ordering, PrincipalRequest request, int expectedVersion) {
        delegate.updatePrincipal(titleId, ordering, request, expectedVersion);
    }

    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = "title-detail", key = "#titleId"),
            @CacheEvict(cacheNames = "six-degrees", allEntries = true)
    })
    public void deletePrincipal(String titleId, int ordering) {
        delegate.deletePrincipal(titleId, ordering);
    }
}
