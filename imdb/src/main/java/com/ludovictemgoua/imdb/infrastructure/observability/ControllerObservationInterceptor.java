package com.ludovictemgoua.imdb.infrastructure.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// Wraps just the controller method invocation in its own span (tracing-design.md §4) - nested inside
// Boot's own auto-instrumented HTTP span and the Spring Security filter-chain spans (auth has already
// happened by the time a handler is resolved), and around the DB/cache spans that happen during the
// method's execution, so a trace clearly separates "framework and auth overhead" from "this endpoint's
// own logic". preHandle/afterCompletion, not a single around-advice, since HandlerInterceptor splits
// "before the handler" and "after the response is fully written" into two separate callbacks on a
// singleton bean - the Observation and its Scope are threaded between them via a request attribute
// rather than an instance field, since one interceptor instance serves every concurrent request.
public class ControllerObservationInterceptor implements HandlerInterceptor {

    private static final String OBSERVATION_ATTRIBUTE = ControllerObservationInterceptor.class.getName() + ".observation";
    private static final String SCOPE_ATTRIBUTE = ControllerObservationInterceptor.class.getName() + ".scope";

    private final ObservationRegistry observationRegistry;

    public ControllerObservationInterceptor(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            // Static resources (swagger-ui assets) and the default error handler go through this
            // interceptor too but aren't a HandlerMethod - nothing worth a span there.
            return true;
        }

        Observation observation = Observation
                .createNotStarted(spanName(handlerMethod), observationRegistry)
                .start();
        Observation.Scope scope = observation.openScope();

        request.setAttribute(OBSERVATION_ATTRIBUTE, observation);
        request.setAttribute(SCOPE_ATTRIBUTE, scope);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                 Exception ex) {
        Object scope = request.getAttribute(SCOPE_ATTRIBUTE);
        Object observation = request.getAttribute(OBSERVATION_ATTRIBUTE);
        if (scope == null || observation == null) {
            return;
        }

        ((Observation.Scope) scope).close();
        Observation obs = (Observation) observation;
        if (ex != null) {
            obs.error(ex);
        }
        obs.stop();
    }

    private static String spanName(HandlerMethod handlerMethod) {
        return handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName();
    }
}
