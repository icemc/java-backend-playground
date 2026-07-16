package com.ludovictemgoua.imdb.application;

import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.application.rest.CreateListRequest;
import com.ludovictemgoua.imdb.application.rest.UpdateListRequest;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import com.ludovictemgoua.imdb.domain.exception.ForbiddenException;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.repository.CustomListRepository;
import com.ludovictemgoua.imdb.domain.repository.WriteResult;
import com.ludovictemgoua.imdb.utils.ImdbIds;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ListUseCaseImpl implements ListUseCase {

    private final CustomListRepository customListRepository;

    public ListUseCaseImpl(CustomListRepository customListRepository) {
        this.customListRepository = customListRepository;
    }

    @Override
    public CustomList create(int userId, CreateListRequest request) {
        return customListRepository.insert(userId, request.name(), request.visibility());
    }

    @Override
    public CustomListView getById(int listId, Optional<Integer> viewerUserId) {
        CustomListView list = findOrThrow(listId);
        boolean isOwner = viewerUserId.isPresent() && viewerUserId.get() == list.userId();
        if (list.visibility() == Visibility.PRIVATE && !isOwner) {
            throw new NotFoundException("No list with id " + listId);
        }
        return list;
    }

    @Override
    public PagedResult<CustomList> getMine(int userId, int page, int size) {
        return customListRepository.findByUser(userId, page, size);
    }

    @Override
    public PagedResult<CustomList> getPublic(int page, int size) {
        return customListRepository.findPublic(page, size);
    }

    @Override
    public void update(int listId, int userId, UpdateListRequest request) {
        CustomListView list = requireOwner(listId, userId);
        WriteResult result = customListRepository.update(listId, request.name(), request.visibility(), request.version());
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("List " + list.id() + " was modified concurrently - refresh and retry");
        }
    }

    @Override
    public void delete(int listId, int userId, int expectedVersion) {
        requireOwner(listId, userId);
        WriteResult result = customListRepository.softDelete(listId, expectedVersion);
        if (result == WriteResult.VERSION_CONFLICT) {
            throw new ConflictException("List " + listId + " was modified concurrently - refresh and retry");
        }
    }

    @Override
    public void addItem(int listId, int userId, String titleId) {
        requireOwner(listId, userId);
        customListRepository.addItem(listId, ImdbIds.parseTitleId(titleId));
    }

    @Override
    public void removeItem(int listId, int userId, String titleId) {
        requireOwner(listId, userId);
        customListRepository.removeItem(listId, ImdbIds.parseTitleId(titleId));
    }

    private CustomListView findOrThrow(int listId) {
        return customListRepository.findById(listId)
                .orElseThrow(() -> new NotFoundException("No list with id " + listId));
    }

    // A non-owner writing to a PRIVATE list gets 404 (existence hidden, same as a read); a non-owner
    // writing to a PUBLIC list gets 403 (existence is already visible, the action is what's denied).
    private CustomListView requireOwner(int listId, int userId) {
        CustomListView list = findOrThrow(listId);
        if (list.userId() == userId) {
            return list;
        }
        if (list.visibility() == Visibility.PRIVATE) {
            throw new NotFoundException("No list with id " + listId);
        }
        throw new ForbiddenException("You do not own list " + listId);
    }
}
