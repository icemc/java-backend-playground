package com.ludovictemgoua.imdb.domain.repository;

import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;

import java.util.Optional;

public interface CustomListRepository {

    CustomList insert(int userId, String name, Visibility visibility);

    Optional<CustomListView> findById(int listId);

    WriteResult update(int listId, String name, Visibility visibility, int expectedVersion);

    WriteResult softDelete(int listId, int expectedVersion);

    PagedResult<CustomList> findByUser(int userId, int page, int size);

    PagedResult<CustomList> findPublic(int page, int size);

    WriteResult addItem(int listId, int titleId);

    WriteResult removeItem(int listId, int titleId);
}
