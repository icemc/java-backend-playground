package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.CastMember;
import com.ludovictemgoua.imdb.domain.model.CreditedPerson;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.SharedTitle;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;

import java.util.List;
import java.util.Optional;

public interface TitleRepository {

    PagedResult<TitleSummary> search(String query, int page, int size);

    Optional<TitleCore> findCore(int tconst);

    List<CreditedPerson> findDirectors(int tconst);

    List<CreditedPerson> findWriters(int tconst);

    List<CastMember> findTopCast(int tconst, int limit);

    int countCast(int tconst);

    List<GenreTopRatedItem> findTopRated(String genre, int limit, int minVotes);

    Optional<SharedTitle> findAnyCommonTitle(int personA, int personB);
}
