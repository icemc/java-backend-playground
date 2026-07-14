package com.ludovictemgoua.imdb.infrastructure.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI imdbOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("imdb API")
                        .description("Search, ratings, and six-degrees over the IMDb dataset, plus JWT auth, "
                                + "admin CRUD over titles/people/credits, and user watchlists/reviews/lists.")
                        .version("v1"));
    }
}
