package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.UpdatePersonRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
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
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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

import java.util.Map;

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@RequestMapping("/api/v1/people")
@Validated
@Tag(name = "People", description = "Six-degrees graph queries and admin CRUD over people")
public class PersonController {

    private final SixDegreesUseCase sixDegreesUseCase;
    private final PersonAdminUseCase personAdminUseCase;

    public PersonController(SixDegreesUseCase sixDegreesUseCase, PersonAdminUseCase personAdminUseCase) {
        this.sixDegreesUseCase = sixDegreesUseCase;
        this.personAdminUseCase = personAdminUseCase;
    }

    @GetMapping("/six-degrees")
    @Operation(operationId = "computeSixDegrees", summary = "Find the degree of separation between two people",
            description = "Generalized \"six degrees of Kevin Bacon\": bidirectional BFS over shared-title "
                    + "co-star edges. personA/personB accept either an exact nm-id or a free-text name; a "
                    + "name matching more than one person returns a disambiguation payload (still HTTP 200, "
                    + "with requiresDisambiguation=true and a list of candidates) instead of an error.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Either a result (with a degree, or degree=null if no path exists within "
                            + "maxDegree) or a disambiguation payload"),
            @ApiResponse(responseCode = "400", description = "maxDegree is out of range (must be 1-7)"),
            @ApiResponse(responseCode = "404", description = "personA or personB matches no known person"),
            @ApiResponse(responseCode = "504",
                    description = "The search took too long to compute against a genuinely hard pair "
                            + "(e.g. two well-connected hub actors with no shared title) and was canceled "
                            + "server-side - try a lower maxDegree or a different pair")
    })
    public ResponseEntity<?> sixDegrees(
            @Parameter(description = "First person: an nm-id (e.g. nm0000102) or a free-text name")
            @RequestParam String personA,
            @Parameter(description = "Second person: an nm-id (e.g. nm0000102) or a free-text name")
            @RequestParam String personB,
            @Parameter(description = "Upper bound on search depth per side of the bidirectional search")
            @RequestParam(defaultValue = "7") @Min(1) @Max(7) int maxDegree) {

        SixDegreesOutcome outcome = sixDegreesUseCase.compute(personA, personB, maxDegree);
        return switch (outcome) {
            case SixDegreesOutcome.Found found -> ResponseEntity.ok(found.result());
            case SixDegreesOutcome.Ambiguous amb -> ResponseEntity.ok(Map.of(
                    "requiresDisambiguation", true, "query", amb.query(), "candidates", amb.candidates()));
            case SixDegreesOutcome.PersonNotFound nf -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ProblemDetail.forStatusAndDetail(
                            HttpStatus.NOT_FOUND, "No person matching: " + nf.query()));
        };
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "createPerson", summary = "Create a new person",
            description = "Admin-only. Inserts a new person using an id from the admin id sequence, "
                    + "separate from the imported IMDb id space so it can never collide with one.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Person created"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin")
    })
    public PersonCore create(@Valid @RequestBody CreatePersonRequest request) {
        return personAdminUseCase.create(request);
    }

    @PutMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updatePerson", summary = "Replace a person's core fields",
            description = "Admin-only. Full replace with optimistic locking - the request's version field "
                    + "must match the row's current version or the update is rejected with 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Person updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No person with that id"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public PersonCore update(@Parameter(description = "IMDb-style person id, e.g. nm0000209") @PathVariable String personId,
                             @Valid @RequestBody UpdatePersonRequest request) {
        return personAdminUseCase.update(personId, request);
    }

    @PatchMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "patchPerson", summary = "Partially update a person",
            description = "Admin-only. Merge-patch semantics - only fields present in the request body are "
                    + "changed, everything else keeps its current value. Still requires the current version.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Person updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No person with that id"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public PersonCore patch(@Parameter(description = "IMDb-style person id, e.g. nm0000209") @PathVariable String personId,
                            @RequestBody PatchPersonRequest request) {
        return personAdminUseCase.patch(personId, request);
    }

    @DeleteMapping("/{personId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deletePerson", summary = "Soft-delete a person",
            description = "Admin-only. Marks the person as deleted; existing cast/crew credits that "
                    + "reference them are left intact.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Person deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No person with that id")
    })
    public void delete(@Parameter(description = "IMDb-style person id, e.g. nm0000209") @PathVariable String personId) {
        personAdminUseCase.delete(personId);
    }
}
