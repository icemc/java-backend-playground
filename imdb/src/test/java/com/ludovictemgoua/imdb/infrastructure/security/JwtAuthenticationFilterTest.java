package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    JwtService jwtService;
    @Mock
    HttpServletRequest request;
    @Mock
    HttpServletResponse response;
    @Mock
    FilterChain chain;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void populatesSecurityContextForAValidBearerToken() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer good-token");
        given(jwtService.parse("good-token"))
                .willReturn(Optional.of(new JwtService.Parsed(7, Set.of(Role.USER), false)));

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        var auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getName()).isEqualTo("7");
        assertThat(auth.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_USER");
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyForARefreshTokenPresentedAsBearerAuth() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer refresh-token");
        given(jwtService.parse("refresh-token"))
                .willReturn(Optional.of(new JwtService.Parsed(7, Set.of(), true)));

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyWithNoAuthorizationHeader() throws Exception {
        given(request.getHeader("Authorization")).willReturn(null);

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void leavesSecurityContextEmptyForAnInvalidToken() throws Exception {
        given(request.getHeader("Authorization")).willReturn("Bearer bad-token");
        given(jwtService.parse("bad-token")).willReturn(Optional.empty());

        new JwtAuthenticationFilter(jwtService).doFilterInternal(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
