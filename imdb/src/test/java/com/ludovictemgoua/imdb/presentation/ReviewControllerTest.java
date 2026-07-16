package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.ReviewUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReviewController.class)
@WithSecurityConfig
class ReviewControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ReviewUseCase reviewUseCase;

    @Test
    void listForTitleIsPubliclyAccessible() throws Exception {
        given(reviewUseCase.listForTitle("tt0000100", 0, 20)).willReturn(new PagedResult<>(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/titles/tt0000100/reviews"))
                .andExpect(status().isOk());
    }

    @Test
    void getMineRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/titles/tt0000100/reviews/me"))
                .andExpect(status().isUnauthorized());
    }
}
