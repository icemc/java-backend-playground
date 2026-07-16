package com.ludovictemgoua.imdb.application.contracts;

import com.ludovictemgoua.imdb.application.rest.CreateListRequest;
import com.ludovictemgoua.imdb.application.rest.UpdateListRequest;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;

import java.util.Optional;

public interface ListUseCase {

    CustomList create(int userId, CreateListRequest request);

    CustomListView getById(int listId, Optional<Integer> viewerUserId);

    PagedResult<CustomList> getMine(int userId, int page, int size);

    PagedResult<CustomList> getPublic(int page, int size);

    void update(int listId, int userId, UpdateListRequest request);

    void delete(int listId, int userId, int expectedVersion);

    void addItem(int listId, int userId, String titleId);

    void removeItem(int listId, int userId, String titleId);
}
