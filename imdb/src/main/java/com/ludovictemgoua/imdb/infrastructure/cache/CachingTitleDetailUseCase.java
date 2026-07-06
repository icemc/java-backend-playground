package com.ludovictemgoua.imdb.infrastructure.cache;

import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.TitleDetailUseCaseImpl;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

@Service
@Primary
public class CachingTitleDetailUseCase implements TitleDetailUseCase {

    private final TitleDetailUseCaseImpl delegate;

    public CachingTitleDetailUseCase(TitleDetailUseCaseImpl delegate) {
        this.delegate = delegate;
    }

    @Override
    @Cacheable(cacheNames = "title-detail", key = "#titleId")
    public TitleDetail getDetail(String titleId) {
        return delegate.getDetail(titleId);
    }
}
