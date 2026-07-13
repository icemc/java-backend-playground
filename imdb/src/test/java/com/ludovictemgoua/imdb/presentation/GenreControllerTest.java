package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.TopRatedUseCase;
import com.ludovictemgoua.imdb.domain.model.GenreTopRatedItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// This endpoint is permanently public/read-only (no admin CRUD is planned for genres), so the
// security filter chain isn't relevant here - disabling filters avoids needing to wire the whole
// security stack into a slice that will never test authorization behavior.
@WebMvcTest(GenreController.class)
@AutoConfigureMockMvc(addFilters = false)
class GenreControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    TopRatedUseCase topRatedUseCase;

    @Test
    void rejectsLimitAboveOneHundred() throws Exception {
        mockMvc.perform(get("/api/v1/genres/Drama/top-rated").param("limit", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsTheRankedListFromTheUseCase() throws Exception {
        given(topRatedUseCase.findTopRated(eq("Drama"), eq(10), isNull()))
                .willReturn(List.of(new GenreTopRatedItem("tt0111161", "The Shawshank Redemption",
                        1994, 9.3, 2900000, 9.2)));

        mockMvc.perform(get("/api/v1/genres/Drama/top-rated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tt0111161"));
    }
}
