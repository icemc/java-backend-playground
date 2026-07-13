package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.WatchlistUseCase;
import com.ludovictemgoua.imdb.domain.model.Visibility;
import com.ludovictemgoua.imdb.domain.model.WatchlistView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WatchlistController.class)
@WithSecurityConfig
class WatchlistControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    WatchlistUseCase watchlistUseCase;

    @Test
    void getOwnWatchlistRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/watchlist"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getOwnWatchlistReturnsItForAnAuthenticatedUser() throws Exception {
        given(watchlistUseCase.getOwn(7)).willReturn(new WatchlistView(1, 7, Visibility.PRIVATE, 0, List.of()));

        mockMvc.perform(get("/api/v1/watchlist").with(SecurityMockMvcRequestPostProcessors.user("7").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
    }

    @Test
    void getUserWatchlistIsAccessibleAnonymouslyWhenPublic() throws Exception {
        given(watchlistUseCase.getForUser(Optional.empty(), 7))
                .willReturn(new WatchlistView(1, 7, Visibility.PUBLIC, 0, List.of()));

        mockMvc.perform(get("/api/v1/users/7/watchlist"))
                .andExpect(status().isOk());
    }
}
