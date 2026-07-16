package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.infrastructure.security.JwtService;
import com.ludovictemgoua.imdb.infrastructure.security.ProblemDetailAccessDeniedHandler;
import com.ludovictemgoua.imdb.infrastructure.security.ProblemDetailAuthenticationEntryPoint;
import com.ludovictemgoua.imdb.infrastructure.security.SecurityConfig;
import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// @WebMvcTest slices don't component-scan the security package, so a test asserting real 401/403
// behavior (as opposed to disabling filters entirely) needs the whole security stack pulled in
// explicitly - this bundles that into one annotation instead of repeating the same import list on
// every controller test that needs it. JwtAuthenticationFilter is deliberately not imported here -
// SecurityConfig's own @Bean method already produces it; importing the class directly too would
// register a second, colliding bean of the same name.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import({SecurityConfig.class, JwtService.class,
        ProblemDetailAuthenticationEntryPoint.class, ProblemDetailAccessDeniedHandler.class})
public @interface WithSecurityConfig {
}
