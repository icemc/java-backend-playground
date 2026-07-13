package com.ludovictemgoua.imdb.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    @Test
    void idOfReturnsEmptyForNullAuthentication() {
        assertThat(CurrentUser.idOf(null)).isEmpty();
    }

    @Test
    void idOfReturnsTheParsedUserIdForARealToken() {
        var auth = new TestingAuthenticationToken("42", null);

        assertThat(CurrentUser.idOf(auth)).contains(42);
    }

    @Test
    void idOfReturnsEmptyForAnonymousAuthentication() {
        var auth = new TestingAuthenticationToken("anonymousUser", null);

        assertThat(CurrentUser.idOf(auth)).isEmpty();
    }

    @Test
    void requireIdThrowsWhenNoUserIsAuthenticated() {
        assertThatThrownBy(() -> CurrentUser.requireId(null)).isInstanceOf(IllegalStateException.class);
    }
}
