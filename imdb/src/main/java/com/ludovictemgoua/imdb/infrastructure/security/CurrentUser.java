package com.ludovictemgoua.imdb.infrastructure.security;

import org.springframework.security.core.Authentication;

import java.util.Optional;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<Integer> idOf(Authentication authentication) {
        if (authentication == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(authentication.getName()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    public static int requireId(Authentication authentication) {
        return idOf(authentication)
                .orElseThrow(() -> new IllegalStateException("No authenticated user in this request"));
    }
}
