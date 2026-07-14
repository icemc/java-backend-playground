package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.AddListItemRequest;
import com.ludovictemgoua.imdb.application.CreateListRequest;
import com.ludovictemgoua.imdb.application.UpdateListRequest;
import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.model.CustomList;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@RequestMapping("/api/v1/lists")
@Validated
@Tag(name = "Custom Lists", description = "User-curated, named lists of titles with public/private visibility")
public class ListController {

    private final ListUseCase listUseCase;

    public ListController(ListUseCase listUseCase) {
        this.listUseCase = listUseCase;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "createList", summary = "Create a new custom list",
            description = "New lists default to PRIVATE unless the request specifies PUBLIC.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "List created"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public CustomList create(Authentication authentication, @Valid @RequestBody CreateListRequest request) {
        return listUseCase.create(CurrentUser.requireId(authentication), request);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "getMyLists", summary = "List the authenticated user's own custom lists",
            description = "Paged; includes both PUBLIC and PRIVATE lists the caller owns.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of the caller's lists"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public PagedResult<CustomList> getMine(
            Authentication authentication,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getMine(CurrentUser.requireId(authentication), page, size);
    }

    @GetMapping("/public")
    @Operation(operationId = "getPublicLists", summary = "List every PUBLIC custom list across all users",
            description = "Publicly accessible; paged.")
    @ApiResponse(responseCode = "200", description = "Paged list of PUBLIC lists")
    public PagedResult<CustomList> getPublic(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return listUseCase.getPublic(page, size);
    }

    @GetMapping("/{listId}")
    @Operation(operationId = "getListById", summary = "Get a custom list and its items",
            description = "Publicly accessible for PUBLIC lists. Returns 404 for a PRIVATE list unless the "
                    + "caller is its owner - existence is hidden, not just access-denied.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The list and its items"),
            @ApiResponse(responseCode = "404", description = "No list with that id, or it is PRIVATE and the caller isn't its owner")
    })
    public CustomListView getById(Authentication authentication,
                                  @Parameter(description = "Numeric list id") @PathVariable int listId) {
        return listUseCase.getById(listId, CurrentUser.idOf(authentication));
    }

    @PutMapping("/{listId}")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateList", summary = "Rename or change the visibility of a list",
            description = "Owner-only. A non-owner gets 403 if the list is PUBLIC (existence already "
                    + "visible) or 404 if it's PRIVATE (existence stays hidden). Optimistic locking via "
                    + "the request's version field.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List updated"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller does not own this PUBLIC list"),
            @ApiResponse(responseCode = "404", description = "No list with that id, or it is PRIVATE and the caller isn't its owner"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public void update(Authentication authentication, @Parameter(description = "Numeric list id") @PathVariable int listId,
                       @Valid @RequestBody UpdateListRequest request) {
        listUseCase.update(listId, CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/{listId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteList", summary = "Delete a custom list",
            description = "Owner-only; same 403-vs-404 visibility rule as updateList.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "List deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller does not own this PUBLIC list"),
            @ApiResponse(responseCode = "404", description = "No list with that id, or it is PRIVATE and the caller isn't its owner"),
            @ApiResponse(responseCode = "409", description = "expectedVersion does not match the current row - refresh and retry")
    })
    public void delete(Authentication authentication, @Parameter(description = "Numeric list id") @PathVariable int listId,
                       @Parameter(description = "Version read from the list being deleted, for optimistic locking")
                       @RequestParam int expectedVersion) {
        listUseCase.delete(listId, CurrentUser.requireId(authentication), expectedVersion);
    }

    @PostMapping("/{listId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "addListItem", summary = "Add a title to a custom list",
            description = "Owner-only; same 403-vs-404 visibility rule as updateList.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Title added"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller does not own this PUBLIC list"),
            @ApiResponse(responseCode = "404", description = "No list with that id, or it is PRIVATE and the caller isn't its owner")
    })
    public void addItem(Authentication authentication, @Parameter(description = "Numeric list id") @PathVariable int listId,
                       @Valid @RequestBody AddListItemRequest request) {
        listUseCase.addItem(listId, CurrentUser.requireId(authentication), request.titleId());
    }

    @DeleteMapping("/{listId}/items/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "removeListItem", summary = "Remove a title from a custom list",
            description = "Owner-only; same 403-vs-404 visibility rule as updateList.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Title removed"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller does not own this PUBLIC list"),
            @ApiResponse(responseCode = "404", description = "No list with that id, or it is PRIVATE and the caller isn't its owner")
    })
    public void removeItem(Authentication authentication, @Parameter(description = "Numeric list id") @PathVariable int listId,
                           @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        listUseCase.removeItem(listId, CurrentUser.requireId(authentication), titleId);
    }
}
