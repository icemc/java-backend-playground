package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.application.TopRatedUseCaseImpl;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class CachingTopRatedUseCase implements TopRatedUseCase {

    private static final Logger log = LoggerFactory.getLogger(CachingTopRatedUseCase.class);

    private final TopRatedUseCaseImpl delegate;

    public CachingTopRatedUseCase(TopRatedUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @Cacheable(cacheNames = "top-rated", key = "#genre + ':' + #limit + ':' + #minVotes")
    public List<GenreTopRatedItem> findTopRated(String genre, int limit, Integer minVotes) {
        log.debug("cache miss, computing: cache=top-rated genre={} limit={} minVotes={}", genre, limit, minVotes);
        return delegate.findTopRated(genre, limit, minVotes);
    }
}
