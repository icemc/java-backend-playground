package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.CreatePersonRequest;
import com.ludovictemgoua.imdb.application.PatchPersonRequest;
import com.ludovictemgoua.imdb.application.UpdatePersonRequest;
import com.ludovictemgoua.imdb.application.contracts.PersonAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import com.ludovictemgoua.imdb.domain.model.PersonCore;
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

@RestController
@RequestMapping("/api/v1/people")
@Validated
public class PersonController {

    private final SixDegreesUseCase sixDegreesUseCase;
    private final PersonAdminUseCase personAdminUseCase;

    public PersonController(SixDegreesUseCase sixDegreesUseCase, PersonAdminUseCase personAdminUseCase) {
        this.sixDegreesUseCase = sixDegreesUseCase;
        this.personAdminUseCase = personAdminUseCase;
    }

    @GetMapping("/six-degrees")
    public ResponseEntity<?> sixDegrees(
            @RequestParam String personA,
            @RequestParam String personB,
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
    public PersonCore create(@Valid @RequestBody CreatePersonRequest request) {
        return personAdminUseCase.create(request);
    }

    @PutMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PersonCore update(@PathVariable String personId, @Valid @RequestBody UpdatePersonRequest request) {
        return personAdminUseCase.update(personId, request);
    }

    @PatchMapping("/{personId}")
    @PreAuthorize("hasRole('ADMIN')")
    public PersonCore patch(@PathVariable String personId, @RequestBody PatchPersonRequest request) {
        return personAdminUseCase.patch(personId, request);
    }

    @DeleteMapping("/{personId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String personId) {
        personAdminUseCase.delete(personId);
    }
}
