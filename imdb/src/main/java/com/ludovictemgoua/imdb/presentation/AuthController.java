package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.LoginRequest;
import com.ludovictemgoua.imdb.application.RegisterRequest;
import com.ludovictemgoua.imdb.application.TokenPair;
import com.ludovictemgoua.imdb.application.contracts.AuthUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, and JWT token refresh")
public class AuthController {

    private final AuthUseCase authUseCase;

    public AuthController(AuthUseCase authUseCase) {
        this.authUseCase = authUseCase;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(operationId = "registerUser", summary = "Register a new user account",
            description = "Creates a USER-role account and immediately issues an access/refresh token "
                    + "pair - no separate login call is needed after registering.")
    @ApiResponses({
            @ApiResponse(responseCode = "201",
                    description = "Account created; response body contains the access and refresh tokens"),
            @ApiResponse(responseCode = "400",
                    description = "Request failed validation (e.g. malformed email, blank password)"),
            @ApiResponse(responseCode = "409", description = "An account with this email already exists")
    })
    public TokenPair register(@Valid @RequestBody RegisterRequest request) {
        return authUseCase.register(request);
    }

    @PostMapping("/login")
    @Operation(operationId = "login", summary = "Log in with email and password",
            description = "Exchanges valid credentials for a new access/refresh token pair.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Credentials valid; response body contains the access and refresh tokens"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "403", description = "Email not found or password incorrect")
    })
    public TokenPair login(@Valid @RequestBody LoginRequest request) {
        return authUseCase.login(request);
    }

    @PostMapping("/refresh")
    @Operation(operationId = "refreshAccessToken", summary = "Exchange a refresh token for a new token pair",
            description = "Issues a fresh access/refresh token pair from a still-valid refresh token, so a "
                    + "user can stay signed in without logging in again.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Refresh token valid; response body contains a new access and refresh token"),
            @ApiResponse(responseCode = "403",
                    description = "Refresh token is invalid, expired, or its user no longer exists")
    })
    public TokenPair refresh(@RequestBody RefreshRequest request) {
        return authUseCase.refresh(request.refreshToken());
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }
}
