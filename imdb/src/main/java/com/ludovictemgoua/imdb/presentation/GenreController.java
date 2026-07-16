package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/genres")
@Validated
@Tag(name = "Genres", description = "Genre-scoped top-rated title rankings")
public class GenreController {

    private final TopRatedUseCase topRatedUseCase;

    public GenreController(TopRatedUseCase topRatedUseCase) {
        this.topRatedUseCase = topRatedUseCase;
    }

    @GetMapping("/{genre}/top-rated")
    @Operation(operationId = "getTopRatedByGenre", summary = "List the top-rated movies in a genre",
            description = "Ranks movies in the given genre by an IMDb-style Bayesian weighted rating, not "
                    + "raw average, so a handful of perfect votes can't outrank a title with broad support.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Ranked list of top-rated movies for the genre (empty if none qualify)"),
            @ApiResponse(responseCode = "400", description = "limit is out of range (must be 1-100)")
    })
    public List<GenreTopRatedItem> topRated(
            @Parameter(description = "Genre name, e.g. \"Action\", \"Drama\" (case-sensitive, matches IMDb's genre list)")
            @PathVariable String genre,
            @Parameter(description = "Maximum number of results to return")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @Parameter(description = "Minimum vote count a title needs to be considered; lower values let "
                    + "small-sample titles compete but shrink harder toward the pool mean. Defaults to a "
                    + "server-configured value if omitted.")
            @RequestParam(required = false) Integer minVotes) {
        return topRatedUseCase.findTopRated(genre, limit, minVotes);
    }
}
