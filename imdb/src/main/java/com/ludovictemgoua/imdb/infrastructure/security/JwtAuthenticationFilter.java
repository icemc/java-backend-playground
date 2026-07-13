package com.ludovictemgoua.imdb.infrastructure.security;

import com.ludovictemgoua.imdb.domain.model.Role;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

// Not a @Component - @WebMvcTest slices auto-detect and try to construct any Filter-typed bean they
// find via component scanning, regardless of whether that slice's test cares about security at all
// (confirmed empirically: a plain @Component here broke every existing controller test, including
// ones that were never going to touch auth, since Spring tried to build this filter and failed on its
// JwtService dependency not being in that slice). Registered as a @Bean inside SecurityConfig instead,
// so it only exists in a context that explicitly imports SecurityConfig.
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring("Bearer ".length());
            jwtService.parse(token).ifPresent(parsed -> authenticate(parsed.userId(), parsed.roles()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(int userId, Set<Role> roles) {
        List<GrantedAuthority> authorities = roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        var auth = new UsernamePasswordAuthenticationToken(String.valueOf(userId), null, authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
