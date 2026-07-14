# OpenAPI UI Design

## 1. Goal

Give the `imdb` API an interactive, browsable API reference. README/LLD both flagged this as deferred
("Swagger/OpenAPI UI is deferred: springdoc-openapi's Initializr `versionRange` doesn't yet cover Spring
Boot 4.1") - that gap has since closed and this design resolves it, adding **both** a Swagger UI and a
Redoc UI, generated from the same OpenAPI document.

## 2. What's confirmed already

`springdoc-openapi-starter-webmvc-ui` 3.0.3 (GitHub release notes: targets Spring Boot 4.0.5) was added to
this project's `pom.xml` as a spike and the full `ImdbApplicationTests` Spring context was started against
it: the context loads cleanly against this project's actual Spring Boot 4.1.0, with no dependency
conflicts, and springdoc auto-registers `/v3/api-docs` and `/swagger-ui.html` on startup (confirmed via
its own startup log lines). The spike change was reverted before this design was written; nothing is
merged yet.

## 3. Approach

Two UIs, one OpenAPI source:

- **Swagger UI**: `springdoc-openapi-starter-webmvc-ui` generates the OpenAPI 3.1 document from the
  existing controllers/DTOs (reflection + the `@Valid`/Bean Validation annotations already present, no
  new annotations required for a baseline result) and serves both the raw document (`/v3/api-docs`) and
  its own interactive UI (`/swagger-ui/index.html`) with zero custom code.
- **Redoc**: a static HTML page (`src/main/resources/static/redoc.html`) that loads the
  `redoc.standalone.js` bundle from its CDN and points it at `spec-url="/v3/api-docs"` - the same document
  Swagger UI reads. Spring Boot serves `src/main/resources/static/**` automatically; no extra dependency
  or controller needed. This is the standard, minimal-effort way to add Redoc to a Spring Boot app.

Both UIs are read-only views over the same generated spec, so there's no risk of them drifting apart -
whichever one a consumer prefers, they see the same contract.

## 4. Security

`SecurityConfig`'s `authorizeHttpRequests` currently permits an explicit allow-list of `GET` paths and
falls back to `.anyRequest().authenticated()` for everything else (Task 1.3's convention, followed by
every public route added since). Without an explicit permit, `/v3/api-docs`, `/swagger-ui/**`, and
`/redoc.html` would all 401. Add to the existing permitAll `GET` list:

```java
"/v3/api-docs/**", "/swagger-ui/**", "/redoc.html"
```

No ordering conflict with existing matchers (none of the current permitAll/authenticated patterns overlap
these paths), so this is a pure addition, not a reordering.

## 5. Metadata

Minimal `application.yaml` properties (`springdoc.swagger-ui.path`, and `OpenAPI` info fields - title,
description, version) via a small `@Bean OpenAPI` in a new `OpenApiConfig`. `infrastructure` is organized
into one subpackage per concern (`cache`, `persistence`, `security`); this adds a new `infrastructure.openapi`
subpackage for the same reason, rather than dropping a generic `config` package in or overloading an
unrelated existing one. Cosmetic only - doesn't change what's documented, just how the title/description
read.

## 6. Testing

One integration test (`OpenApiIntegrationTest`, matching the `*IntegrationTest` naming convention so it
runs under Failsafe against a real Spring context) asserting, unauthenticated:

- `GET /v3/api-docs` returns `200` with a body containing `"openapi"`
- `GET /swagger-ui/index.html` returns `200`
- `GET /redoc.html` returns `200`

This matches the project's established practice of verifying every new route actually works rather than
assuming the wiring is correct from the annotations/static file alone.

## 7. Files touched

- `pom.xml` - one new dependency
- `src/main/java/.../infrastructure/openapi/OpenApiConfig.java` (new) - the `OpenAPI` metadata bean
- `src/main/java/.../infrastructure/security/SecurityConfig.java` - three new permitAll path patterns
- `src/main/resources/static/redoc.html` (new)
- `src/main/resources/application.yaml` - optional `springdoc.*` properties if defaults aren't desired
- `src/test/java/.../infrastructure/OpenApiIntegrationTest.java` (new)
- `README.md` - one line under **API** pointing at `/swagger-ui/index.html` and `/redoc.html`

## 8. Out of scope

- Annotating every controller method with `@Operation`/`@ApiResponse` for richer descriptions - springdoc
  produces a correct, useful document from the existing code without them; hand-annotating every endpoint
  is a much larger, separate effort with no functional benefit, and can be layered on incrementally later
  if desired.
- Authentication for the docs UI itself - the API's own read endpoints are already public; the docs
  describing them are treated the same way, consistent with the rest of this project's public-GET
  convention.
