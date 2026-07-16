package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.TitleDetailUseCaseImpl;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.domain.model.RatingView;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
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
@ContextConfiguration(classes = CachingTitleDetailUseCaseTest.CacheTestConfig.class)
class CachingTitleDetailUseCaseTest {

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("title-detail");
        }

        @Bean
        TitleDetailUseCaseImpl delegate() {
            return mock(TitleDetailUseCaseImpl.class);
        }

        // @Primary here (not just on the class) - see CachingTitleSearchUseCaseTest for why.
        @Bean
        @org.springframework.context.annotation.Primary
        CachingTitleDetailUseCase cachingTitleDetailUseCase(TitleDetailUseCaseImpl delegate) {
            return new CachingTitleDetailUseCase(delegate);
        }
    }

    // Typed by interface - see CachingTitleSearchUseCaseTest for why the concrete class doesn't work.
    @Autowired
    TitleDetailUseCase target;
    @Autowired
    TitleDetailUseCaseImpl delegate;

    @Test
    void secondCallWithTheSameTitleIdIsServedFromCache() {
        var detail = new TitleDetail("tt0111161", "The Shawshank Redemption", "The Shawshank Redemption",
                "movie", 1994, null, 142, List.of("Drama"), new RatingView(9.3, 2900000),
                List.of(), List.of(), List.of(), 0, 0.0, 0);
        given(delegate.getDetail("tt0111161")).willReturn(detail);

        target.getDetail("tt0111161");
        target.getDetail("tt0111161");

        verify(delegate, times(1)).getDetail("tt0111161");
    }
}
