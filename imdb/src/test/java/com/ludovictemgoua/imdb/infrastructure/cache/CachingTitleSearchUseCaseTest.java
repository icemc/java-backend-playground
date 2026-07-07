package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.TitleSearchUseCaseImpl;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
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

// A slice test, not a full @SpringBootTest: only caching infrastructure + a mock delegate, no
// database or Testcontainers. This is the mechanical check the old DistanceCache design (LLD §2.1)
// needed reasoning about carefully to get right; now it's just "does @Cacheable actually cache."
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CachingTitleSearchUseCaseTest.CacheTestConfig.class)
class CachingTitleSearchUseCaseTest {

    @Configuration
    @EnableCaching
    static class CacheTestConfig {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("title-search");
        }

        @Bean
        TitleSearchUseCaseImpl delegate() {
            return mock(TitleSearchUseCaseImpl.class);
        }

        // @Primary here (not just on the class): a @Bean factory method doesn't inherit @Primary from
        // the returned object's runtime class the way component-scanning a @Service does in production -
        // it must be declared on the method itself to disambiguate against the delegate bean below.
        @Bean
        @org.springframework.context.annotation.Primary
        CachingTitleSearchUseCase cachingTitleSearchUseCase(TitleSearchUseCaseImpl delegate) {
            return new CachingTitleSearchUseCase(delegate);
        }
    }

    // Typed by interface, not the concrete CachingTitleSearchUseCase: @EnableCaching proxies any bean
    // with @Cacheable methods, and since this bean implements an interface, Spring defaults to a JDK
    // dynamic proxy - which satisfies the interface type but is not assignable to the concrete class.
    @Autowired
    TitleSearchUseCase target;
    @Autowired
    TitleSearchUseCaseImpl delegate;

    @Test
    void secondCallWithIdenticalArgumentsIsServedFromCacheNotTheDelegate() {
        var result = new PagedResult<TitleSummary>(List.of(), 0, 0, 20);
        given(delegate.search("matrix", 0, 20)).willReturn(result);

        target.search("matrix", 0, 20);
        target.search("matrix", 0, 20);

        verify(delegate, times(1)).search("matrix", 0, 20);
    }

    @Test
    void differentArgumentsAreNotConflated() {
        given(delegate.search("matrix", 0, 20)).willReturn(new PagedResult<>(List.of(), 0, 0, 20));
        given(delegate.search("inception", 0, 20)).willReturn(new PagedResult<>(List.of(), 0, 0, 20));

        target.search("matrix", 0, 20);
        target.search("inception", 0, 20);

        verify(delegate, times(1)).search("matrix", 0, 20);
        verify(delegate, times(1)).search("inception", 0, 20);
    }
}
