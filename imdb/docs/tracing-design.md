# End-to-End Request Tracing — Design

## Problem

Every request already produces a real OpenTelemetry trace that reaches Tempo successfully - confirmed
by querying Tempo directly for a live trace during this design's investigation. But two things are
missing:

1. **Log correlation is broken for the two most important log lines.** The LLD (§7) claims `traceId`/
   `spanId` are "already populated by Micrometer Tracing... included in every line automatically." This
   is false today: tested empirically (real requests, inspected the actual JSON log output) and found
   `traceId` on *zero* log lines, including error logs. Root cause (confirmed by decompiling the actual
   Spring Boot 4.1 jars, not assumed): the MDC-population mechanism genuinely exists and works
   (`OpenTelemetryTracingAutoConfiguration.otelSlf4JEventListener()` is a real, auto-registered bean),
   but `RequestLoggingFilter` runs at `Ordered.HIGHEST_PRECEDENCE` - *outside* the filter
   (`ServerHttpObservationFilter`) that actually opens the span whose scope triggers MDC population. Its
   "request started" log fires before the span exists; its "request completed" log fires in a `finally`
   block after the span has already closed. Everything logged from *within* the request (repository
   DEBUG logs, `ApiExceptionHandler`'s error log) already gets a correct `traceId` - it's specifically the
   two lines bracketing the entire request that don't.

2. **Nothing below the HTTP/security layer is instrumented.** A trace today shows "this request took
   220ms" with zero visibility into how much of that was a SQL query, a Redis round-trip, or actual
   business logic. This app uses raw JDBC (HikariCP + `NamedParameterJdbcTemplate`, no ORM) and Spring's
   declarative `@Cacheable`/`@CacheEvict` over Redis (no manual `RedisTemplate` calls) - neither gets
   automatic span coverage from Spring Boot's own auto-instrumentation, which only covers the HTTP/
   Security filter chain.

## Goals

- Every log line for a request - from "request started" through business logic through "request
  completed" - carries the same `traceId`.
- A trace visible in Tempo shows the full breakdown: HTTP/security (already works) → controller →
  cache → database, all properly nested under one trace.
- Reuse Spring Boot's own, already-correct distributed-trace-context extraction (an incoming W3C
  `traceparent` header from an upstream caller) rather than reimplementing it.

## Non-goals

- New Grafana dashboards. Tempo's own trace-search UI and the existing Loki↔Tempo↔Prometheus
  datasource correlation (already provisioned) are sufficient for this work; nothing new is proposed
  here.
- Reducing the existing Spring Security filter-chain span verbosity (12+ small spans per request). Left
  as-is - useful if auth itself ever needs debugging.
- Tuning trace sampling (`management.tracing.sampling.probability: 1.0` stays as-is, matching the
  existing "full sampling for this exercise" decision).

## Design

### 1. Fix log correlation: reorder `RequestLoggingFilter`

Reorder `RequestLoggingFilter` to run just *inside* `ServerHttpObservationFilter` instead of around it
(exact numeric `@Order` value to be confirmed empirically against the running app during
implementation, the same way every other Boot-4.1-specific detail this session has been confirmed
against real behavior rather than assumed). Its "request started" and "request completed" logs then
execute within the already-open span scope, so both automatically pick up `traceId`/`spanId` via the
existing (already-working, just previously out-of-scope) `Slf4JEventListener` mechanism.

Deliberately **not** implemented by having `RequestLoggingFilter` open its own span: that would mean
reimplementing Boot's incoming-header trace-context extraction ourselves, which
`ServerHttpObservationFilter` already does correctly. Reordering reuses that logic instead of
duplicating (and risking subtly breaking) it.

### 2. Database spans: `datasource-micrometer`

Add `net.ttddyy.observation:datasource-micrometer-spring-boot`, which wraps the HikariCP-backed
`DataSource` bean (via a `BeanPostProcessor`, no manual `DataSource` bean redefinition) so every
connection acquisition and every SQL statement becomes its own Observation/span automatically -
zero changes needed across the ~8 repository classes.

This gives connection-acquisition its own visible span duration - directly relevant given this
session's earlier HikariCP pool-exhaustion incidents (found only through log/stack-trace archaeology
under load); with this in place, that class of problem would show up immediately as an outsized
connection-acquire span in a trace waterfall.

Exact span/tag names and compatibility with this project's exact Boot 4.1.0 / HikariCP versions to be
verified empirically during implementation before relying on them, matching the diligence already
applied to the springdoc integration - the "connection acquire" / "INSERT INTO ..." labels in the trace
shape below are illustrative of the expected granularity, not a confirmed exact API contract yet.

### 3. Cache spans: Lettuce native tracing + hit/miss tagging

Two parts, no new dependency (`lettuce-core:7.5.2.RELEASE` is already on the classpath transitively):

- Configure the `LettuceConnectionFactory`'s `ClientResources` with the existing `ObservationRegistry`
  bean, so every real Redis command (`GET`, `SET`, `DEL`) becomes its own span with real round-trip
  latency. Chosen over wrapping the `Caching*UseCase` decorator methods (thin `@Cacheable`/`@CacheEvict`
  pass-throughs) because instrumenting Lettuce directly isolates actual Redis latency from DB-fallthrough
  latency on a miss, which a method-level span around the decorator wouldn't distinguish.
- Decorate the `redisCacheWriter` bean (`CacheConfig`, currently a plain
  `RedisCacheWriter.create(connectionFactory, ...::collectStatistics)` call with no wrapper) so its
  `get(...)` method, after delegating, tags the *currently active* Observation with
  `cache.result=hit`/`miss` based on whether the returned value was null, via the injected
  `ObservationRegistry`.

  Known limitation, accepted deliberately: because Lettuce closes its own Redis-command span
  synchronously inside the `get()` call, by the time the decorator's code runs the tag is more likely
  to land on the parent controller span (§4) than on the specific `GET` command span underneath it.
  Still useful in practice - the controller span shows `cache.result=miss` as an attribute, with the
  Redis `GET` and any subsequent DB spans visible as its children, so the outcome and the timing
  breakdown are both visible together even if not on the literal same span.

### 4. Controller span: a `HandlerInterceptor`

A `HandlerInterceptor` (`preHandle`/`afterCompletion`) starts an Observation right before the resolved
controller method is invoked and stops it right after the response is fully written - nested inside the
security filter-chain spans (auth has already happened by the time a handler is resolved) and around
everything in §2/§3, since DB and cache calls happen during the controller method's execution. Named
from the resolved `HandlerMethod` (e.g. `PersonController#create`), readable in Tempo without
cross-referencing source code.

Chosen over annotating every controller method with `@Observed`: the 8 controllers already carry heavy
OpenAPI annotations (`@Operation`, `@ApiResponses`, etc.) on all 48 endpoint methods from an earlier
documentation pass. One interceptor, registered once via `WebMvcConfigurer`, covers all of them
automatically and stays correct as endpoints are added, without growing already-large controller files
further.

### Resulting trace shape

For a cache-miss request, end to end:

```
request (traceId X, every span below shares it)
└─ http post /api/v1/titles                         (Boot's existing auto span)
   └─ security filterchain ...                       (existing, unchanged)
   └─ PersonController#create                         (new, §4)
      ├─ cache.result=miss                            (new, §3 - attribute, not a span)
      ├─ redis GET title-detail::...                   (new, §3)
      ├─ connection acquire                            (new, §2)
      ├─ INSERT INTO title_basics ...                   (new, §2)
      └─ redis SET title-detail::...                   (new, §3)
```

Every log line from "request started" through this whole tree to "request completed" carries
`traceId=X` (§1).

## Testing & verification

- **Automated**: an integration test attaching a Logback `ListAppender` during a real request
  (`@SpringBootTest`/`@AutoConfigureMockMvc`, matching `OpenApiIntegrationTest`'s existing pattern),
  asserting the captured "request started" and "request completed" log entries both carry a non-blank
  `traceId`. This is the one piece of this design with a crisp, automatable pass/fail.
- **Live verification** for §2-§4 (actual span presence/shape): generate real traffic against the live
  containers, then query Tempo's own API directly for the resulting trace and confirm the expected span
  names appear (`connection acquire`, `redis GET ...`, `PersonController#create`, etc.) - the same
  method already used to confirm the *current* gap during this design's own investigation. Not a
  permanent automated test: these are third-party library integrations being wired up, not custom logic
  worth unit-testing in isolation, and Tempo isn't part of the Testcontainers test environment.

## Dependencies

- **New**: `net.ttddyy.observation:datasource-micrometer-spring-boot` (version/Boot-4.1 compatibility to
  be confirmed empirically during implementation).
- **None needed** for Lettuce tracing or the controller interceptor - both use what's already on the
  classpath (`lettuce-core`, `micrometer-observation`, Spring MVC).

## Documentation

Update LLD §7 (Observability Wiring) to describe the new span coverage and correct its current
inaccurate claim about automatic MDC population, following this project's established pattern of
documenting the real root cause of every non-obvious fix (§7.1's four dashboard bugs, §8's five
load-test bugs).
