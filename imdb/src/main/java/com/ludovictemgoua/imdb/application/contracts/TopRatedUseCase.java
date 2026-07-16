package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;

import java.util.List;

public interface TopRatedUseCase {

    List<GenreTopRatedItem> findTopRated(String genre, int limit, Integer minVotes);
}
