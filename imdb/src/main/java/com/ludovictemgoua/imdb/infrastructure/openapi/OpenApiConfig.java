package com.ludovictemgoua.imdb.infrastructure.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";
    private static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI imdbOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("imdb API")
                        .description("Search, ratings, and six-degrees over the IMDb dataset, plus JWT auth, "
                                + "admin CRUD over titles/people/credits, and user watchlists/reviews/lists.")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the access token returned by /api/v1/auth/login or "
                                        + "/api/v1/auth/register - click Authorize once and every "
                                        + "\"try it out\" call below will include it automatically.")));
    }

    // Every @ApiResponse in this codebase's controllers only declares a responseCode/description - it
    // never repeats a schema, so springdoc falls back to the method's own return type for every
    // response it lists, success or not. That's wrong for anything 4xx/5xx: ApiExceptionHandler (and
    // Spring Security's entry point/access-denied handler) always return RFC 7807 ProblemDetail there,
    // never the success payload. This customizer runs once over the fully-generated document and
    // rewrites every non-2xx response to point at the one shared ProblemDetail schema, instead of
    // hand-repeating a content/schema override on every @ApiResponse across every controller.
    //
    // The schema itself is registered here too, not on the imdbOpenApi() bean above - confirmed
    // empirically that a schema added to that bean's own Components gets silently dropped by
    // springdoc's own component resolution before the document is served, since nothing in the
    // codebase actually returns a ProblemDetail-typed value for springdoc's scanner to notice it's
    // used. GlobalOpenApiCustomizers run as the last step in building the document, so registering it
    // here - after our own $refs already exist - is what makes it survive.
    @Bean
    public GlobalOpenApiCustomizer problemDetailResponseCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null) {
                openApi.setComponents(new Components());
            }
            openApi.getComponents().addSchemas(PROBLEM_DETAIL_SCHEMA, problemDetailSchema());

            openApi.getPaths().values().forEach(pathItem ->
                    pathItem.readOperations().forEach(operation -> {
                        if (operation.getResponses() == null) {
                            return;
                        }
                        operation.getResponses().forEach((code, response) -> {
                            if (isErrorStatusCode(code)) {
                                response.setContent(new Content().addMediaType("application/problem+json",
                                        new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM_DETAIL_SCHEMA))));
                            }
                        });
                    }));
        };
    }

    private static boolean isErrorStatusCode(String code) {
        return code.length() == 3 && (code.charAt(0) == '4' || code.charAt(0) == '5');
    }

    // OpenAPI 3.1 (what springdoc 3.x generates by default) moved "type" from a single string to a
    // "types" set to align with JSON Schema - the legacy .type(String) setter alone is silently
    // dropped by the 3.1 serializer, confirmed empirically (the generated document had every property
    // missing its type entirely). .types(Set.of(...)) is what actually survives serialization here.
    private static Schema<?> problemDetailSchema() {
        return new Schema<>()
                .types(Set.of("object"))
                .description("RFC 7807 problem details, returned for every non-2xx response on this API.")
                .addProperty("type", new Schema<>().types(Set.of("string")).example("about:blank"))
                .addProperty("title", new Schema<>().types(Set.of("string")).example("Not Found"))
                .addProperty("status", new Schema<>().types(Set.of("integer")).example(404))
                .addProperty("detail", new Schema<>().types(Set.of("string")).example("No title with id tt9999999"))
                .addProperty("instance", new Schema<>().types(Set.of("string")).example("/api/v1/titles/tt9999999"));
    }
}
