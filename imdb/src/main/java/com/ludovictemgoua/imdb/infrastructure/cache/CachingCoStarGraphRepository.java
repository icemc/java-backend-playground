package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.infrastructure.persistence.JdbcCoStarGraphRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.Optional;

// Caching lives at the repository level here, not the use-case level like the three Title* use
// cases above - the true shortest distance between two person ids is a clean, unambiguous cache key
// independent of anything SixDegreesUseCase does with names/disambiguation/maxDegree (LLD §6).
@Repository
@Primary
public class CachingCoStarGraphRepository implements CoStarGraphRepository {

    private final JdbcCoStarGraphRepository delegate;

    public CachingCoStarGraphRepository(JdbcCoStarGraphRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    @Cacheable(cacheNames = "six-degrees",
            key = "T(java.lang.Math).min(#personA, #personB) + '-' + T(java.lang.Math).max(#personA, #personB)")
    public Optional<GraphPath> findShortestPath(int personA, int personB) {
        return delegate.findShortestPath(personA, personB);
    }
}
