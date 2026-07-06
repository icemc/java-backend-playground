package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import org.springframework.stereotype.Service;

@Service
public class TitleSearchUseCaseImpl implements TitleSearchUseCase {

    private final TitleRepository titleRepository;

    public TitleSearchUseCaseImpl(TitleRepository titleRepository) {
        this.titleRepository = titleRepository;
    }

    @Override
    public PagedResult<TitleSummary> search(String query, int page, int size) {
        return titleRepository.search(query, page, size);
    }
}
