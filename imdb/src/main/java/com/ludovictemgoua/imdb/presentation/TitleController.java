package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/titles")
@Validated
public class TitleController {

    private final TitleSearchUseCase searchUseCase;
    private final TitleDetailUseCase detailUseCase;

    public TitleController(TitleSearchUseCase searchUseCase, TitleDetailUseCase detailUseCase) {
        this.searchUseCase = searchUseCase;
        this.detailUseCase = detailUseCase;
    }

    @GetMapping("/search")
    public PagedResult<TitleSummary> search(
            @RequestParam @NotBlank String title,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return searchUseCase.search(title, page, size);
    }

    @GetMapping("/{titleId}")
    public TitleDetail get(@PathVariable String titleId) {
        return detailUseCase.getDetail(titleId);
    }
}
