# OpenAPI UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a browsable, interactive API reference to the `imdb` service - both Swagger UI and Redoc, generated from one OpenAPI document, publicly accessible like the rest of the API's read endpoints.

**Architecture:** `springdoc-openapi-starter-webmvc-ui` introspects the existing controllers/DTOs to generate the OpenAPI 3.1 document at `/v3/api-docs` and serves Swagger UI itself; a static `redoc.html` page (Spring Boot's default static-resource serving, no extra dependency) renders the same document via the Redoc CDN bundle. `SecurityConfig` gets three new public path patterns.

**Tech Stack:** `springdoc-openapi-starter-webmvc-ui` 3.0.3, Redoc (CDN-hosted `redoc.standalone.js`, no new dependency).

## Global Constraints

- Spring Boot parent version: `4.1.0` (do not upgrade as part of this work).
- `springdoc-openapi-starter-webmvc-ui` version: `3.0.3` exactly - confirmed via GitHub release notes and an empirical spike (full Spring context start) to work against this project's Boot 4.1.0; do not substitute another version without re-verifying the same way.
- Every new public path must be added to `SecurityConfig`'s existing `permitAll()` allow-list convention (Task 1.3's pattern, followed by every public route in this project) - never add a broad catch-all, never disable security for a whole path prefix beyond what's needed.
- Tests use AssertJ (`org.assertj.core.api.Assertions.assertThat`), matching every existing test in this codebase - do not introduce Hamcrest or other assertion libraries.
- Integration tests follow the `*IntegrationTest.java` naming convention (Failsafe-run, Testcontainers-backed) - see `pom.xml`'s Surefire/Failsafe include/exclude configuration.

---

### Task 1: Swagger UI (dependency, metadata bean, security permit)

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiConfig.java`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java`
- Test: `src/test/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiIntegrationTest.java`

**Interfaces:**
- Consumes: nothing new from elsewhere in the codebase - `OpenApiConfig` only depends on `io.swagger.v3.oas.models.OpenAPI`/`Info`, transitively provided by the new dependency.
- Produces: a public `GET /v3/api-docs` (the generated OpenAPI document) and a public `GET /swagger-ui/index.html` (the interactive UI). Task 2's Redoc page reads the same `/v3/api-docs` document this task makes public.

- [ ] **Step 1: Write the failing test**

```java
package com.ludovictemgoua.imdb.infrastructure.openapi;

import com.ludovictemgoua.imdb.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsIsPubliclyAccessible() throws Exception {
        var result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("\"openapi\"");
    }

    @Test
    void swaggerUiIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
```

This test needs a real Spring context (not a `@WebMvcTest` slice) so the real `SecurityFilterChain` and the real springdoc auto-configuration are both exercised together - matching `ImdbApplicationTests`' `@Import(TestcontainersConfiguration.class) @SpringBootTest` pattern, plus `@AutoConfigureMockMvc` (no `addFilters = false` - security must actually run for this test to mean anything).

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=OpenApiIntegrationTest`
Expected: FAIL - both requests return `401` (Spring Security's filter chain intercepts every request before `DispatcherServlet` gets a chance to resolve a handler, so an unprotected path with no permit rule is rejected regardless of whether springdoc is even on the classpath yet).

- [ ] **Step 3: Add the dependency**

In `pom.xml`, add immediately after the `flyway-database-postgresql` dependency (before the `micrometer-registry-prometheus` block):

```xml
		<dependency>
			<groupId>org.springdoc</groupId>
			<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
			<version>3.0.3</version>
		</dependency>
```

- [ ] **Step 4: Create `OpenApiConfig`**

```java
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
```

`infrastructure` is organized into one subpackage per concern (`cache`, `persistence`, `security`) - this adds a new `infrastructure.openapi` subpackage for the same reason, rather than a generic `config` package.

- [ ] **Step 5: Add the security permit**

In `SecurityConfig.java`, change:

```java
                        .requestMatchers("/actuator/**", "/api/v1/auth/**").permitAll()
```

to:

```java
                        .requestMatchers("/actuator/**", "/api/v1/auth/**",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
```

(`/swagger-ui.html` is a distinct single-segment path springdoc registers as a redirect entry point, separate from the `/swagger-ui/**`-prefixed static assets - both need permitting; confirmed via the earlier spike's own startup log: `"SpringDoc /swagger-ui.html endpoint is enabled by default"`.)

- [ ] **Step 6: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=OpenApiIntegrationTest`
Expected: PASS, both tests green.

- [ ] **Step 7: Commit**

```bash
git add pom.xml src/main/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiConfig.java src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java src/test/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiIntegrationTest.java
git commit -m "Add Swagger UI via springdoc-openapi, public API docs endpoints"
```

---

### Task 2: Redoc

**Files:**
- Create: `src/main/resources/static/redoc.html`
- Modify: `src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java`
- Modify: `src/test/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiIntegrationTest.java`

**Interfaces:**
- Consumes: the `GET /v3/api-docs` document Task 1 made public (Redoc fetches it client-side via `spec-url`).
- Produces: a public `GET /redoc.html`.

- [ ] **Step 1: Extend the test with the failing case**

Add to `OpenApiIntegrationTest`:

```java
    @Test
    void redocIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/redoc.html"))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=OpenApiIntegrationTest`
Expected: FAIL - `redocIsPubliclyAccessible` returns `401` (no permit rule and no file yet); the two Task 1 tests still pass.

- [ ] **Step 3: Create the static Redoc page**

```html
<!DOCTYPE html>
<html>
  <head>
    <title>imdb API - Redoc</title>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>body { margin: 0; padding: 0; }</style>
  </head>
  <body>
    <redoc spec-url="/v3/api-docs"></redoc>
    <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
  </body>
</html>
```

Spring Boot serves `src/main/resources/static/**` automatically at the site root - no controller or extra dependency needed.

- [ ] **Step 4: Add the security permit**

In `SecurityConfig.java`, change:

```java
                        .requestMatchers("/actuator/**", "/api/v1/auth/**",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
```

to:

```java
                        .requestMatchers("/actuator/**", "/api/v1/auth/**",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**", "/redoc.html").permitAll()
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify -Dit.test=OpenApiIntegrationTest`
Expected: PASS, all three tests green.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/static/redoc.html src/main/java/com/ludovictemgoua/imdb/infrastructure/security/SecurityConfig.java src/test/java/com/ludovictemgoua/imdb/infrastructure/openapi/OpenApiIntegrationTest.java
git commit -m "Add a public Redoc page reading the same generated OpenAPI document"
```

---

### Task 3: Documentation and final verification

**Files:**
- Modify: `README.md`

**Interfaces:** none - this task only verifies and documents; no new production code.

- [ ] **Step 1: Run the full local verification sequence**

```bash
JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q test
JAVA_HOME="/c/Program Files/Java/jdk-21" ./mvnw -q failsafe:integration-test failsafe:verify
```
Expected: PASS for every unit and integration test in the project (nothing from before this plan should have been weakened or broken).

- [ ] **Step 2: Update `README.md`**

In the **API** section, find this exact line:

```markdown
All errors are RFC 7807 `ProblemDetail` (404 for unknown IDs, 400 for malformed IDs/out-of-range `maxDegree`/missing params, 405 for the wrong HTTP method). Full contracts, request/response shapes, and error handling: [`docs/low-level-design.md`](docs/low-level-design.md) §4/§9.
```

Insert this new paragraph immediately after it:

```markdown

Interactive API docs, generated from the live controllers: [`/swagger-ui/index.html`](http://localhost:8080/swagger-ui/index.html) (Swagger UI) or [`/redoc.html`](http://localhost:8080/redoc.html) (Redoc), both reading the same generated document at `/v3/api-docs`.
```

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Document the new Swagger UI and Redoc endpoints"
```
