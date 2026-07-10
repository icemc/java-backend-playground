package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.application.TitleSearchUseCaseImpl;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

// @Primary: whichever bean wins here is what every consumer of TitleSearchUseCase (controllers) gets
// by constructor injection, with zero awareness that caching exists. Swap caching technology, or drop
// it entirely, by changing only this class - TitleSearchUseCaseImpl and every controller are untouched.
@Service
@Primary
public class CachingTitleSearchUseCase implements TitleSearchUseCase {

    private static final Logger log = LoggerFactory.getLogger(CachingTitleSearchUseCase.class);

    private final TitleSearchUseCaseImpl delegate;

    public CachingTitleSearchUseCase(TitleSearchUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @Cacheable(cacheNames = "title-search", key = "#query + ':' + #page + ':' + #size")
    public PagedResult<TitleSummary> search(String query, int page, int size) {
        // @Cacheable only calls the annotated method on a miss (Spring's caching proxy intercepts
        // hits before this body ever runs) - so this line is inherently a cache-miss signal, not
        // something that needs its own hit/miss branching. Aggregate hit ratio is already tracked via
        // Micrometer's auto-bound cache metrics (LLD §6/§7, the Cache Hit Ratio dashboard); this is
        // the per-request complement for tracing a specific slow request back to "it missed cache".
        log.debug("cache miss, computing: cache=title-search query={} page={} size={}", query, page, size);
        return delegate.search(query, page, size);
    }
}
