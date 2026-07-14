package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.PrincipalRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PrincipalCredit;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@RequestMapping("/api/v1/titles")
@Validated
@Tag(name = "Titles", description = "Search, detail, and admin CRUD over titles, crew, cast, and ratings")
public class TitleController {

    private final TitleSearchUseCase searchUseCase;
    private final TitleDetailUseCase detailUseCase;
    private final TitleAdminUseCase titleAdminUseCase;

    public TitleController(TitleSearchUseCase searchUseCase, TitleDetailUseCase detailUseCase,
                            TitleAdminUseCase titleAdminUseCase) {
        this.searchUseCase = searchUseCase;
        this.detailUseCase = detailUseCase;
        this.titleAdminUseCase = titleAdminUseCase;
    }

    @GetMapping("/search")
    @Operation(operationId = "searchTitles", summary = "Fuzzy search titles by name",
            description = "Trigram similarity search over primary and original title, tolerant of typos "
                    + "and partial matches. Publicly accessible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of matching titles"),
            @ApiResponse(responseCode = "400", description = "title is blank, or page/size is out of range")
    })
    public PagedResult<TitleSummary> search(
            @Parameter(description = "Search text matched fuzzily against the title") @RequestParam @NotBlank String title,
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return searchUseCase.search(title, page, size);
    }

    @GetMapping("/{titleId}")
    @Operation(operationId = "getTitleById", summary = "Get full detail for a title",
            description = "Returns metadata, the original IMDb rating, the aggregate user rating from "
                    + "reviews, directors/writers, and top-billed cast. Publicly accessible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Title detail"),
            @ApiResponse(responseCode = "400", description = "titleId is not a valid tt-prefixed id"),
            @ApiResponse(responseCode = "404", description = "No title with that id")
    })
    public TitleDetail get(
            @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        return detailUseCase.getDetail(titleId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "createTitle", summary = "Create a new title",
            description = "Admin-only. Inserts a new title using an id from the admin id sequence, "
                    + "separate from the imported IMDb id space so it can never collide with one.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Title created"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin")
    })
    public TitleCore create(@Valid @RequestBody CreateTitleRequest request) {
        return titleAdminUseCase.create(request);
    }

    @PutMapping("/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateTitle", summary = "Replace a title's core fields",
            description = "Admin-only. Full replace with optimistic locking - the request's version field "
                    + "must match the row's current version or the update is rejected with 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Title updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public TitleCore update(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                            @Valid @RequestBody UpdateTitleRequest request) {
        return titleAdminUseCase.update(titleId, request);
    }

    @PatchMapping("/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "patchTitle", summary = "Partially update a title",
            description = "Admin-only. Merge-patch semantics - only fields present in the request body are "
                    + "changed, everything else keeps its current value. Still requires the current version.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Title updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public TitleCore patch(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                           @RequestBody PatchTitleRequest request) {
        return titleAdminUseCase.patch(titleId, request);
    }

    @DeleteMapping("/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteTitle", summary = "Soft-delete a title",
            description = "Admin-only. Marks the title as deleted - it stops appearing in search, detail, "
                    + "and top-rated results, but existing cast/crew credit records that reference it are "
                    + "left intact.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Title deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id")
    })
    public void delete(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        titleAdminUseCase.delete(titleId);
    }

    @PutMapping("/{titleId}/crew")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "upsertTitleCrew", summary = "Set a title's directors and writers",
            description = "Admin-only. Replaces the full directors/writers list for the title in one call "
                    + "- this is a full replace, not a partial add.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Crew updated"),
            @ApiResponse(responseCode = "400", description = "Request references a malformed person id"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id")
    })
    public void upsertCrew(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                           @RequestBody CrewRequest request) {
        titleAdminUseCase.upsertCrew(titleId, request);
    }

    @PutMapping("/{titleId}/rating")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "upsertTitleRating", summary = "Set a title's official rating",
            description = "Admin-only. Creates or overwrites the title's average rating and vote count; "
                    + "also revives a previously soft-deleted rating row if one existed.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rating set"),
            @ApiResponse(responseCode = "400", description = "Request failed validation (rating range, vote count)"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id")
    })
    public void upsertRating(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                             @Valid @RequestBody RatingRequest request) {
        titleAdminUseCase.upsertRating(titleId, request);
    }

    @DeleteMapping("/{titleId}/rating")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteTitleRating", summary = "Remove a title's official rating",
            description = "Admin-only. Soft-deletes the rating row; the title's IMDb rating then reads as "
                    + "absent until a new rating is set.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Rating removed"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id, or it has no rating to remove")
    })
    public void deleteRating(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        titleAdminUseCase.deleteRating(titleId);
    }

    @GetMapping("/{titleId}/principals")
    @Operation(operationId = "getTitlePrincipals", summary = "List every cast/crew credit for a title",
            description = "Returns the full, uncapped list of principal credits (unlike title detail's "
                    + "top-N cast), ordered by billing order. Publicly accessible; returns an empty list, "
                    + "not 404, for an unknown title id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of credits (possibly empty)"),
            @ApiResponse(responseCode = "400", description = "titleId is not a valid tt-prefixed id")
    })
    public List<PrincipalCredit> getAllPrincipals(
            @Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId) {
        return titleAdminUseCase.getAllPrincipals(titleId);
    }

    @PostMapping("/{titleId}/principals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "addTitlePrincipal", summary = "Add a cast/crew credit to a title",
            description = "Admin-only. Inserts one principal credit at the given billing order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Credit added"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No title with that id")
    })
    public void addPrincipal(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                             @Valid @RequestBody PrincipalRequest request) {
        titleAdminUseCase.addPrincipal(titleId, request);
    }

    @PutMapping("/{titleId}/principals/{ordering}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateTitlePrincipal", summary = "Update a cast/crew credit",
            description = "Admin-only. Updates the category/job/characters for the credit at the given "
                    + "billing order. Note: unlike other admin writes on this API, a credit that doesn't "
                    + "exist is not distinguished from a stale version - both report 409, since the "
                    + "composite (titleId, ordering) key is something the caller must already know.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credit updated"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "409",
                    description = "expectedVersion does not match the current row, or no credit exists at that ordering")
    })
    public void updatePrincipal(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                                @Parameter(description = "Billing order of the credit to update, from getTitlePrincipals")
                                @PathVariable int ordering,
                                @Valid @RequestBody PrincipalRequest request,
                                @Parameter(description = "Version read from the credit being updated, for optimistic locking")
                                @RequestParam int expectedVersion) {
        titleAdminUseCase.updatePrincipal(titleId, ordering, request, expectedVersion);
    }

    @DeleteMapping("/{titleId}/principals/{ordering}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteTitlePrincipal", summary = "Remove a cast/crew credit",
            description = "Admin-only. Soft-deletes the credit at the given billing order.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Credit removed"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No credit exists at that ordering for that title")
    })
    public void deletePrincipal(@Parameter(description = "IMDb-style title id, e.g. tt0111161") @PathVariable String titleId,
                                @Parameter(description = "Billing order of the credit to remove, from getTitlePrincipals")
                                @PathVariable int ordering) {
        titleAdminUseCase.deletePrincipal(titleId, ordering);
    }
}
