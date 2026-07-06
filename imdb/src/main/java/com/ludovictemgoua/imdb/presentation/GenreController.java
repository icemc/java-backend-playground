package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
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
public class GenreController {

    private final TopRatedUseCase topRatedUseCase;

    public GenreController(TopRatedUseCase topRatedUseCase) {
        this.topRatedUseCase = topRatedUseCase;
    }

    @GetMapping("/{genre}/top-rated")
    public List<GenreTopRatedItem> topRated(
            @PathVariable String genre,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) Integer minVotes) {
        return topRatedUseCase.findTopRated(genre, limit, minVotes);
    }
}
