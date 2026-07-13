package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.AddListItemRequest;
import com.ludovictemgoua.imdb.application.CreateListRequest;
import com.ludovictemgoua.imdb.application.UpdateListRequest;
import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/lists")
@Validated
public class ListController {

    private final ListUseCase listUseCase;

    public ListController(ListUseCase listUseCase) {
        this.listUseCase = listUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomList create(Authentication authentication, @Valid @RequestBody CreateListRequest request) {
        return listUseCase.create(CurrentUser.requireId(authentication), request);
    }

    @GetMapping("/me")
    public PagedResult<CustomList> getMine(
            Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getMine(CurrentUser.requireId(authentication), page, size);
    }

    @GetMapping("/public")
    public PagedResult<CustomList> getPublic(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getPublic(page, size);
    }

    @GetMapping("/{listId}")
    public CustomListView getById(Authentication authentication, @PathVariable int listId) {
        return listUseCase.getById(listId, CurrentUser.idOf(authentication));
    }

    @PutMapping("/{listId}")
    public void update(Authentication authentication, @PathVariable int listId,
                       @Valid @RequestBody UpdateListRequest request) {
        listUseCase.update(listId, CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication authentication, @PathVariable int listId, @RequestParam int expectedVersion) {
        listUseCase.delete(listId, CurrentUser.requireId(authentication), expectedVersion);
    }

    @PostMapping("/{listId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public void addItem(Authentication authentication, @PathVariable int listId,
                       @Valid @RequestBody AddListItemRequest request) {
        listUseCase.addItem(listId, CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/{listId}/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeItem(Authentication authentication, @PathVariable int listId, @PathVariable String titleId) {
        listUseCase.removeItem(listId, CurrentUser.requireId(authentication), titleId);
    }
}
