package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.ListUseCase;
import com.ludovictemgoua.imdb.domain.model.CustomListView;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ListController.class)
@WithSecurityConfig
class ListControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ListUseCase listUseCase;

    @Test
    void getPublicListsIsAccessibleAnonymously() throws Exception {
        given(listUseCase.getPublic(0, 20)).willReturn(new PagedResult<>(List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/v1/lists/public"))
                .andExpect(status().isOk());
    }

    @Test
    void getByIdIsAccessibleAnonymouslyForAPublicList() throws Exception {
        given(listUseCase.getById(1, Optional.empty()))
                .willReturn(new CustomListView(1, 7, "Public", Visibility.PUBLIC, 0, List.of()));

        mockMvc.perform(get("/api/v1/lists/1"))
                .andExpect(status().isOk());
    }

    @Test
    void createRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/lists")
                        .contentType("application/json")
                        .content("""
                                {"name":"My List","visibility":"PRIVATE"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMineRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/lists/me"))
                .andExpect(status().isUnauthorized());
    }
}
