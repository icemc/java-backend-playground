package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.SixDegreesOutcome;
import com.ludovictemgoua.imdb.application.contracts.SixDegreesUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/people")
@Validated
public class PersonController {

    private final SixDegreesUseCase sixDegreesUseCase;

    public PersonController(SixDegreesUseCase sixDegreesUseCase) {
        this.sixDegreesUseCase = sixDegreesUseCase;
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
}
