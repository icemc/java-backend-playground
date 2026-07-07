package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.TitleDetailUseCase;
import com.ludovictemgoua.imdb.application.contracts.TitleSearchUseCase;
import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.TitleSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TitleController.class)
class TitleControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    TitleSearchUseCase titleSearchUseCase;
    @MockitoBean
    TitleDetailUseCase titleDetailUseCase;

    @Test
    void searchRequiresANonBlankTitleParam() throws Exception {
        mockMvc.perform(get("/api/v1/titles/search").param("title", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchRejectsPageSizeAboveOneHundred() throws Exception {
        mockMvc.perform(get("/api/v1/titles/search").param("title", "matrix").param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchReturnsThePagedResultFromTheUseCase() throws Exception {
        var summary = new TitleSummary("tt0133093", "The Matrix", "The Matrix", "movie", 1999, null);
        given(titleSearchUseCase.search("matrix", 0, 20))
                .willReturn(new PagedResult<>(List.of(summary), 1, 0, 20));

        mockMvc.perform(get("/api/v1/titles/search").param("title", "matrix"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("tt0133093"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void getReturns404WhenTheUseCaseThrowsNotFound() throws Exception {
        given(titleDetailUseCase.getDetail("tt9999999"))
                .willThrow(new NotFoundException("No title with id tt9999999"));

        mockMvc.perform(get("/api/v1/titles/tt9999999"))
                .andExpect(status().isNotFound());
    }
}
