package com.ludovictemgoua.imdb.infrastructure.observability;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ObservabilityWebMvcConfig implements WebMvcConfigurer {

    private final ObservationRegistry observationRegistry;

    // WebMvcConfigurer beans are pulled into @WebMvcTest slices regardless of what else that slice
    // excludes, but @WebMvcTest also explicitly disables tracing autoconfiguration - so no
    // ObservationRegistry bean exists there (confirmed empirically: every @WebMvcTest controller
    // test failed context startup with a constructor-injection UnsatisfiedDependencyException before
    // this fallback). ObservationRegistry.NOOP makes the interceptor harmless in that case instead of
    // failing the whole slice.
    public ObservabilityWebMvcConfig(ObjectProvider<ObservationRegistry> observationRegistry) {
        this.observationRegistry = observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ControllerObservationInterceptor(observationRegistry));
    }
}
