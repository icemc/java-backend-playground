package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.application.TitleSearchUseCaseImpl;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// @Primary: whichever bean wins here is what every consumer of TitleSearchUseCase (controllers) gets
// by constructor injection, with zero awareness that caching exists. Swap caching technology, or drop
// it entirely, by changing only this class - TitleSearchUseCaseImpl and every controller are untouched.
@Service
@Primary
public class CachingTitleSearchUseCase implements TitleSearchUseCase {

    private final TitleSearchUseCaseImpl delegate;

    public CachingTitleSearchUseCase(TitleSearchUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @Cacheable(cacheNames = "title-search", key = "#query + ':' + #page + ':' + #size")
    public PagedResult<TitleSummary> search(String query, int page, int size) {
        return delegate.search(query, page, size);
    }
}
