package com.ludovictemgoua.imdb.presentation;

import com.ludovictemgoua.imdb.domain.exception.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

// Extends ResponseEntityExceptionHandler rather than starting from a bare @RestControllerAdvice - it
// already correctly handles the whole family of standard Spring MVC exceptions (missing/malformed
// request params, wrong HTTP method, unsupported media type, no static resource, etc.) with the
// right status codes. Discovered why this matters the hard way: a bare-bones catch-all
// @ExceptionHandler(Exception.class) below is broad enough to shadow ALL of that built-in handling
// (Spring resolves to the most specific declared exception type, and plain Exception was the only
// thing registered for any of them) - a browser's routine /favicon.ico request, a missing query
// param, and a wrong HTTP verb all turned into a 500 "unexpected error" instead of their real 404/
// 400/405. Extending the base class restores that handling for free; our own handlers below only
// need to cover what it doesn't already know about (this API's own domain exceptions) plus the
// generic catch-all for anything genuinely unexpected.
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        // 404 is an expected, routine outcome for this API (a title/person id that doesn't exist) -
        // not an application fault, so DEBUG rather than WARN/ERROR.
        log.debug("not found: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadId(IllegalArgumentException ex) {
        log.debug("bad request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // @Validated on a @RestController triggers Spring's older AOP-based MethodValidationInterceptor,
    // which throws a plain ConstraintViolationException - not the newer HandlerMethodValidationException
    // that Spring MVC handles automatically. Confirmed empirically (a first pass omitted this handler on
    // the assumption the newer auto-handling applied here; it doesn't for @Validated-triggered validation).
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
        log.debug("bad request: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    // Catch-all for anything not already handled above - without this, an unexpected failure (a bug,
    // a downstream outage) would only surface as a generic 500 from Spring Boot's default error
    // controller, with the real cause visible only if you already knew to go dig through a stack
    // trace dump. Logging it at ERROR with the full exception here means it's never silently lost.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("unexpected error", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
