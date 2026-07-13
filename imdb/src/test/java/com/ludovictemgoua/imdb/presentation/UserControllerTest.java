package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.Role;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@WithSecurityConfig
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    UserUseCase userUseCase;

    @Test
    void getOwnRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getOwnReturnsTheProfileForAnAuthenticatedUser() throws Exception {
        given(userUseCase.getOwnProfile(7))
                .willReturn(new UserProfile(7, "a@example.com", "Ada", "bio", Role.USER, 0));

        mockMvc.perform(get("/api/v1/users/me").with(SecurityMockMvcRequestPostProcessors.user("7").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Ada"));
    }

    @Test
    void getPublicProfileIsAccessibleAnonymously() throws Exception {
        given(userUseCase.getPublicProfile(7)).willReturn(new PublicUserProfile(7, "Ada"));

        mockMvc.perform(get("/api/v1/users/7")).andExpect(status().isOk());
    }

    @Test
    void deleteAccountRequiresAdminRole() throws Exception {
        mockMvc.perform(delete("/api/v1/users/7")
                        .with(SecurityMockMvcRequestPostProcessors.user("1").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
