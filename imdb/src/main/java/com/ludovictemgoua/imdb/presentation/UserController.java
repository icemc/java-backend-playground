package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.RoleRequest;
import com.ludovictemgoua.imdb.application.UpdateProfileRequest;
import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import static com.ludovictemgoua.imdb.infrastructure.openapi.OpenApiConfig.BEARER_AUTH;

@RestController
@Validated
@Tag(name = "Users", description = "Account profile management and admin user administration")
public class UserController {

    private final UserUseCase userUseCase;

    public UserController(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
    }

    @GetMapping("/api/v1/users/me")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "getOwnProfile", summary = "Get the authenticated user's full profile",
            description = "Returns the caller's own profile, including fields not exposed on the public "
                    + "profile view (email, role, version).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Caller's profile"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public UserProfile getOwn(Authentication authentication) {
        return userUseCase.getOwnProfile(CurrentUser.requireId(authentication));
    }

    @PutMapping("/api/v1/users/me")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateOwnProfile", summary = "Update the authenticated user's profile",
            description = "Updates display name and bio with optimistic locking - the request's version "
                    + "field must match the row's current version or the update is rejected with 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Profile updated; response body is the new state"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "409", description = "version does not match the current row - refresh and retry")
    })
    public UserProfile updateOwn(Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        return userUseCase.updateOwnProfile(CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/api/v1/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteOwnAccount", summary = "Soft-delete the authenticated user's account",
            description = "Self-service account deletion.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied")
    })
    public void deleteOwn(Authentication authentication) {
        userUseCase.deleteOwnAccount(CurrentUser.requireId(authentication));
    }

    @GetMapping("/api/v1/users/{userId}")
    @Operation(operationId = "getPublicUserProfile", summary = "Get a user's public profile",
            description = "Returns only the fields safe to expose to anyone (id, display name) - no "
                    + "email, role, or other private data. Publicly accessible.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Public profile"),
            @ApiResponse(responseCode = "404", description = "No user with that id")
    })
    public PublicUserProfile getPublicProfile(
            @Parameter(description = "Numeric user id") @PathVariable int userId) {
        return userUseCase.getPublicProfile(userId);
    }

    @GetMapping("/api/v1/users")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "listAllUsers", summary = "List all user accounts",
            description = "Admin-only. Paged list of every account's full profile.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged list of user profiles"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin")
    })
    public PagedResult<UserProfile> listAll(
            @Parameter(description = "Zero-based page number") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Results per page") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userUseCase.listAll(page, size);
    }

    @PutMapping("/api/v1/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "updateUserRole", summary = "Change a user's role",
            description = "Admin-only. Promotes or demotes a user between USER and ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Role updated"),
            @ApiResponse(responseCode = "400", description = "Request failed validation"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No user with that id")
    })
    public void updateRole(@Parameter(description = "Numeric user id") @PathVariable int userId,
                           @Valid @RequestBody RoleRequest request) {
        userUseCase.updateRole(userId, request.role());
    }

    @DeleteMapping("/api/v1/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = BEARER_AUTH)
    @Operation(operationId = "deleteUserAccount", summary = "Soft-delete any user's account",
            description = "Admin-only. Same effect as self-service deletion, callable against any account.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Account deleted"),
            @ApiResponse(responseCode = "401", description = "No valid Bearer token supplied"),
            @ApiResponse(responseCode = "403", description = "Caller is authenticated but not an admin"),
            @ApiResponse(responseCode = "404", description = "No user with that id")
    })
    public void deleteAccount(@Parameter(description = "Numeric user id") @PathVariable int userId) {
        userUseCase.deleteAccount(userId);
    }
}
