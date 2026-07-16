package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.domain.model.GraphPath;
import com.ludovictemgoua.imdb.domain.repository.CoStarGraphRepository;
import com.ludovictemgoua.imdb.infrastructure.persistence.JdbcCoStarGraphRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CachingCoStarGraphRepositoryTest.CacheTestConfig.class)
class CachingCoStarGraphRepositoryTest {

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("six-degrees");
        }

        @Bean
        JdbcCoStarGraphRepository delegate() {
            return mock(JdbcCoStarGraphRepository.class);
        }

        // @Primary here (not just on the class) - see CachingTitleSearchUseCaseTest for why.
        @Bean
        @org.springframework.context.annotation.Primary
        CachingCoStarGraphRepository cachingCoStarGraphRepository(JdbcCoStarGraphRepository delegate) {
            return new CachingCoStarGraphRepository(delegate);
        }
    }

    // Typed by interface - see CachingTitleSearchUseCaseTest for why the concrete class doesn't work.
    @Autowired
    CoStarGraphRepository target;
    @Autowired
    JdbcCoStarGraphRepository delegate;

    @Test
    void sameUnorderedPairSharesOneCacheEntryRegardlessOfArgumentOrder() {
        given(delegate.findShortestPath(1, 2)).willReturn(Optional.of(new GraphPath(1, List.of(1, 2))));

        var first = target.findShortestPath(1, 2);
        var second = target.findShortestPath(2, 1);

        assertThat(first).isEqualTo(second);
        verify(delegate, times(1)).findShortestPath(1, 2);
        verify(delegate, never()).findShortestPath(2, 1);
    }
}
