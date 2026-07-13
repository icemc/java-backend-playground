package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.CreateTitleRequest;
import com.ludovictemgoua.imdb.application.CrewRequest;
import com.ludovictemgoua.imdb.application.PatchTitleRequest;
import com.ludovictemgoua.imdb.application.RatingRequest;
import com.ludovictemgoua.imdb.application.UpdateTitleRequest;
import com.ludovictemgoua.imdb.application.contracts.TitleAdminUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleCore;
import com.ludovictemgoua.imdb.domain.model.TitleDetail;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
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

@RestController
@RequestMapping("/api/v1/titles")
@Validated
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public TitleCore create(@Valid @RequestBody CreateTitleRequest request) {
        return titleAdminUseCase.create(request);
    }

    @PutMapping("/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public TitleCore update(@PathVariable String titleId, @Valid @RequestBody UpdateTitleRequest request) {
        return titleAdminUseCase.update(titleId, request);
    }

    @PatchMapping("/{titleId}")
    @PreAuthorize("hasRole('ADMIN')")
    public TitleCore patch(@PathVariable String titleId, @RequestBody PatchTitleRequest request) {
        return titleAdminUseCase.patch(titleId, request);
    }

    @DeleteMapping("/{titleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(@PathVariable String titleId) {
        titleAdminUseCase.delete(titleId);
    }

    @PutMapping("/{titleId}/crew")
    @PreAuthorize("hasRole('ADMIN')")
    public void upsertCrew(@PathVariable String titleId, @RequestBody CrewRequest request) {
        titleAdminUseCase.upsertCrew(titleId, request);
    }

    @PutMapping("/{titleId}/rating")
    @PreAuthorize("hasRole('ADMIN')")
    public void upsertRating(@PathVariable String titleId, @Valid @RequestBody RatingRequest request) {
        titleAdminUseCase.upsertRating(titleId, request);
    }

    @DeleteMapping("/{titleId}/rating")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteRating(@PathVariable String titleId) {
        titleAdminUseCase.deleteRating(titleId);
    }
}
