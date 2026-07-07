package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.TopRatedUseCaseImpl;
import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
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

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CachingTopRatedUseCaseTest.CacheTestConfig.class)
class CachingTopRatedUseCaseTest {

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("top-rated");
        }

        @Bean
        TopRatedUseCaseImpl delegate() {
            return mock(TopRatedUseCaseImpl.class);
        }

        // @Primary here (not just on the class) - see CachingTitleSearchUseCaseTest for why.
        @Bean
        @org.springframework.context.annotation.Primary
        CachingTopRatedUseCase cachingTopRatedUseCase(TopRatedUseCaseImpl delegate) {
            return new CachingTopRatedUseCase(delegate);
        }
    }

    // Typed by interface - see CachingTitleSearchUseCaseTest for why the concrete class doesn't work.
    @Autowired
    TopRatedUseCase target;
    @Autowired
    TopRatedUseCaseImpl delegate;

    @Test
    void secondCallWithTheSameArgumentsIsServedFromCache() {
        List<GenreTopRatedItem> result = List.of();
        given(delegate.findTopRated("Drama", 10, 1000)).willReturn(result);

        target.findTopRated("Drama", 10, 1000);
        target.findTopRated("Drama", 10, 1000);

        verify(delegate, times(1)).findTopRated("Drama", 10, 1000);
    }
}
