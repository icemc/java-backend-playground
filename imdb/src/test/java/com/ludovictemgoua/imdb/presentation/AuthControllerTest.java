package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.rest.LoginRequest;
import com.ludovictemgoua.imdb.application.rest.RegisterRequest;
import com.ludovictemgoua.imdb.application.rest.TokenPair;
import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import com.ludovictemgoua.imdb.domain.exception.ConflictException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// /api/v1/auth/** is always public (permitAll) and this test doesn't probe authorization
// boundaries, only the endpoints' own business logic - disabling filters avoids needing to import
// the whole security stack for a slice that will never exercise it.
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @MockitoBean
    AuthUseCase authUseCase;

    @Test
    void registerReturns201WithTokens() throws Exception {
        given(authUseCase.register(new RegisterRequest("ada@example.com", "password123", "Ada")))
                .willReturn(new TokenPair("access", "refresh"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("ada@example.com", "password123", "Ada"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access"));
    }

    @Test
    void registerReturns409ForADuplicateEmail() throws Exception {
        given(authUseCase.register(new RegisterRequest("ada@example.com", "password123", "Ada")))
                .willThrow(new ConflictException("An account with this email already exists"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest("ada@example.com", "password123", "Ada"))))
                .andExpect(status().isConflict());
    }

    @Test
    void loginReturnsTokensForValidCredentials() throws Exception {
        given(authUseCase.login(new LoginRequest("ada@example.com", "password123")))
                .willReturn(new TokenPair("access", "refresh"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("ada@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("refresh"));
    }
}
