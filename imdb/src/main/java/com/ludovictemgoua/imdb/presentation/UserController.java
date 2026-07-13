package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.application.RoleRequest;
import com.ludovictemgoua.imdb.application.UpdateProfileRequest;
import com.ludovictemgoua.imdb.application.contracts.UserUseCase;
import com.ludovictemgoua.imdb.domain.model.PagedResult;
import com.ludovictemgoua.imdb.domain.model.PublicUserProfile;
import com.ludovictemgoua.imdb.domain.model.UserProfile;
import com.ludovictemgoua.imdb.infrastructure.security.CurrentUser;
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

@RestController
@Validated
public class UserController {

    private final UserUseCase userUseCase;

    public UserController(UserUseCase userUseCase) {
        this.userUseCase = userUseCase;
    }

    @GetMapping("/api/v1/users/me")
    public UserProfile getOwn(Authentication authentication) {
        return userUseCase.getOwnProfile(CurrentUser.requireId(authentication));
    }

    @PutMapping("/api/v1/users/me")
    public UserProfile updateOwn(Authentication authentication, @Valid @RequestBody UpdateProfileRequest request) {
        return userUseCase.updateOwnProfile(CurrentUser.requireId(authentication), request);
    }

    @DeleteMapping("/api/v1/users/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOwn(Authentication authentication) {
        userUseCase.deleteOwnAccount(CurrentUser.requireId(authentication));
    }

    @GetMapping("/api/v1/users/{userId}")
    public PublicUserProfile getPublicProfile(@PathVariable int userId) {
        return userUseCase.getPublicProfile(userId);
    }

    @GetMapping("/api/v1/users")
    @PreAuthorize("hasRole('ADMIN')")
    public PagedResult<UserProfile> listAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return userUseCase.listAll(page, size);
    }

    @PutMapping("/api/v1/users/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public void updateRole(@PathVariable int userId, @Valid @RequestBody RoleRequest request) {
        userUseCase.updateRole(userId, request.role());
    }

    @DeleteMapping("/api/v1/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteAccount(@PathVariable int userId) {
        userUseCase.deleteAccount(userId);
    }
}
