package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;

public interface TitleSearchUseCase {

    PagedResult<TitleSummary> search(String query, int page, int size);
}
