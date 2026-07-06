package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import com.ludovictemgoua.imdb.domain.repository.TitleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TopRatedUseCaseImpl implements TopRatedUseCase {

    private final TitleRepository titleRepository;
    private final int defaultMinVotes;

    public TopRatedUseCaseImpl(TitleRepository titleRepository,
                         @Value("${top-rated.default-min-votes}") int defaultMinVotes) {
        this.titleRepository = titleRepository;
        this.defaultMinVotes = defaultMinVotes;
    }

    @Override
    public List<GenreTopRatedItem> findTopRated(String genre, int limit, Integer minVotes) {
        int effectiveMinVotes = minVotes == null ? defaultMinVotes : minVotes;
        return titleRepository.findTopRated(genre, limit, effectiveMinVotes);
    }
}
