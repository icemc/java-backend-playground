# IMDb CRUD Expansion - Design Document

| | |
|---|---|
| Author | Ludovic Temgoua Abanda |
| Status | Approved (brainstormed interactively; see decisions below) |
| Date | 2026-07-13 |
| Related docs | `imdb/docs/product-design.md`, `imdb/docs/low-level-design.md`, `imdb/docs/REQUIREMENTS.md` |
| Supersedes | PDD §4's original non-goals: "Authentication/authorization" and "Write endpoints of any kind" |

## 1. Purpose and Scope

The original `imdb` API (PDD/LLD) is a deliberately read-only layer over an externally-seeded, immutable
IMDb dataset - no auth, no writes, 24h cache TTLs justified entirely by "nothing ever changes." This
document extends that into a full CRUD RESTful API, adding two new layers on top of the existing
read-only core:

1. **A user-generated content layer** - accounts, watchlists, reviews, and custom lists - entirely new
   tables the app owns outright.
2. **An admin curation layer** - CRUD over the core IMDb entities themselves (titles, people, ratings,
   cast/crew), gated behind an `ADMIN` role, writing into the same tables the original design treated as
   permanently immutable.

Everything in the original PDD/LLD (search, title detail, top-rated, six-degrees, the observability stack,
the three-tier test pipeline) stays as-is and stays public/unauthenticated. This is a strict addition, not
a breaking change.

## 2. Decisions Made (interactive brainstorming)

| Decision | Chosen | Rejected alternatives |
|---|---|---|
| CRUD scope | Both: user-generated layer AND admin CRUD over core entities | User-layer only; core-entity CRUD only |
| Auth mechanism | Self-issued JWT via Spring Security (hand-rolled filter, not a full OAuth2 resource server) | External OAuth2/OIDC IdP (Keycloak); static API keys |
| Delete semantics | Soft delete everywhere (`deleted_at`) | Hard delete everywhere; hybrid (soft for user content, hard for core) |
| List/watchlist visibility | Both PUBLIC and PRIVATE, owner's choice per list | Private-only |

## 3. Authentication & Authorization

- New dependency: `spring-boot-starter-security`. JWTs are both issued and validated by this app, so
  authentication is a small hand-rolled `JwtAuthenticationFilter` (reads `Authorization: Bearer <token>`,
  validates signature/expiry, populates `SecurityContext`) rather than the full
  `spring-boot-starter-oauth2-resource-server` machinery built for validating externally-issued tokens.
- `infrastructure.security.JwtService`: issues/parses HMAC-SHA256-signed tokens, secret from an env var
  (`JWT_SECRET`, no default in any committed config). Claims: `sub` (userId), `roles` (`["USER"]` or
  `["USER","ADMIN"]`), `exp`.
- Passwords hashed with BCrypt (`PasswordEncoder` bean, Spring Security's default).
- Two token types: a short-lived access token (15 min) and a longer-lived refresh token (7 days), issued
  as a pair on login/refresh.
- A bootstrap admin account makes the `ADMIN` role reachable at all on a fresh stack (there is otherwise no
  way to grant it). Flyway (`V5`, see §7) creates the `users` table but does **not** insert this row - a
  Flyway migration runs before the Spring context (and its `PasswordEncoder` bean) exists, so it can't
  BCrypt-hash a password cleanly. Instead, an `ApplicationRunner` bean
  (`infrastructure.security.BootstrapAdminRunner`) runs once after the context is fully up: if no user with
  the configured bootstrap email (`IMDB_BOOTSTRAP_ADMIN_EMAIL`) exists, it creates one with role `ADMIN`,
  password BCrypt-hashed from `IMDB_BOOTSTRAP_ADMIN_PASSWORD` via the same `PasswordEncoder` bean every
  other registration uses - idempotent on every restart, no separate migration-time password handling.
- **Error-shape consistency**: Spring Security's default 401/403 responses aren't RFC 7807 `ProblemDetail`
  - they're a bare, framework-shaped response inconsistent with the rest of this API's error handling
  (LLD §9's `ApiExceptionHandler` work). Fixed via custom `AuthenticationEntryPoint` (401) and
  `AccessDeniedHandler` (403) beans that produce `ProblemDetail`, registered in `SecurityConfig`, so a
  caller never sees two different error shapes depending on whether Spring MVC or Spring Security rejected
  the request.
- Authorization is method-level (`@PreAuthorize("hasRole('ADMIN')")` on admin write methods,
  `@PreAuthorize("isAuthenticated()")` on user-owned-resource methods), not a blanket URL-pattern rule -
  this keeps the read endpoints (search, title detail, top-rated, six-degrees, and the new public
  review/list-browsing endpoints) genuinely public with zero filter overhead, while write endpoints are
  individually and explicitly protected.

## 4. New Resources: Users, Watchlists, Reviews, Custom Lists

### 4.1 Users (`users` table)

| Endpoint | Auth | Description |
|---|---|---|
| `POST /api/v1/auth/register` | Public | Create account (`email`, `password`, `displayName`) -> role `USER`. 409 if email taken. |
| `POST /api/v1/auth/login` | Public | `{ email, password }` -> `{ accessToken, refreshToken }` |
| `POST /api/v1/auth/refresh` | Public (valid refresh token) | `{ refreshToken }` -> new access token |
| `GET /api/v1/users/me` | User | Own full profile |
| `PUT /api/v1/users/me` | User | Update own profile (`displayName`, `bio`) |
| `DELETE /api/v1/users/me` | User | Soft-delete own account |
| `GET /api/v1/users/{userId}` | Public | Limited public profile (`displayName` only) |
| `GET /api/v1/users` | Admin | Paginated list of all users |
| `PUT /api/v1/users/{userId}/role` | Admin | Grant/revoke `ADMIN` |
| `DELETE /api/v1/users/{userId}` | Admin | Moderation: soft-delete any account |

### 4.2 Watchlist (`watchlists` + `watchlist_items`, one watchlist per user)

| Endpoint | Auth | Description |
|---|---|---|
| `GET /api/v1/watchlist` | User | Own watchlist + items (auto-created on first access) |
| `POST /api/v1/watchlist/items` | User | Add `{ titleId }` |
| `DELETE /api/v1/watchlist/items/{titleId}` | User | Remove a title |
| `PUT /api/v1/watchlist/visibility` | User | `{ visibility: PUBLIC\|PRIVATE }` |
| `GET /api/v1/users/{userId}/watchlist` | Public if `PUBLIC` | View another user's watchlist |

### 4.3 Reviews (`reviews` table, one per `(user, title)`, always public)

| Endpoint | Auth | Description |
|---|---|---|
| `POST /api/v1/titles/{titleId}/reviews` | User | Create own review+rating. 409 if one already exists for this pair - use PUT instead. |
| `GET /api/v1/titles/{titleId}/reviews` | Public | Paginated reviews for a title |
| `GET /api/v1/titles/{titleId}/reviews/me` | User | Own review for that title |
| `PUT /api/v1/titles/{titleId}/reviews/me` | User | Update own review |
| `DELETE /api/v1/titles/{titleId}/reviews/me` | User | Soft-delete own review |
| `GET /api/v1/users/{userId}/reviews` | Public | All reviews a user has written |

Title detail gains two new, additive fields derived from this table: `userRatingAverage` and
`userRatingCount`, shown alongside the existing IMDb `rating` field. Both are `null`/`0` if no reviews
exist yet.

### 4.4 Custom lists (`lists` + `list_items`)

| Endpoint | Auth | Description |
|---|---|---|
| `POST /api/v1/lists` | User | `{ name, visibility }` |
| `GET /api/v1/lists/me` | User | All own lists (any visibility) |
| `GET /api/v1/lists/public` | Public | Paginated discovery of public lists across all users |
| `GET /api/v1/lists/{listId}` | Public if `PUBLIC`, else owner-only | View a list + its items |
| `PUT /api/v1/lists/{listId}` | Owner | Rename / change visibility |
| `DELETE /api/v1/lists/{listId}` | Owner | Soft-delete |
| `POST /api/v1/lists/{listId}/items` | Owner | Add `{ titleId }` |
| `DELETE /api/v1/lists/{listId}/items/{titleId}` | Owner | Remove a title |

Accessing a `PRIVATE` list/watchlist you don't own returns `404`, not `403` - existence of another user's
private list is not itself information this API discloses.

## 5. Admin CRUD over the Core IMDb Entities

**ID-collision wrinkle**: `tconst`/`nconst` are plain integers, seeded by the external
`abanda/imdb-postgresql` image and already densely allocated. Admin-created titles/people need IDs that
can never collide with an existing seeded row. Fix: two Postgres `SEQUENCE`s
(`title_id_seq`/`person_id_seq`), initialized once in `V6` to `MAX(tconst)+1`/`MAX(nconst)+1`, used *only*
for app-created rows. Seeded rows keep their original IDs untouched. New rows translate through the same
`utils.ImdbIds` formatter already at the API boundary - a caller never sees anything different from a
normal `tt`/`nm` ID.

### 5.1 Titles

| Endpoint | Description |
|---|---|
| `POST /api/v1/titles` | Create (`primaryTitle`, `originalTitle`, `titleType`, `startYear`, `endYear`, `runtimeMinutes`, `genres`) |
| `PUT /api/v1/titles/{titleId}` | Full update (requires current `version`, §6.1) |
| `PATCH /api/v1/titles/{titleId}` | Partial update (merge-patch: only send fields to change) |
| `DELETE /api/v1/titles/{titleId}` | Soft-delete |
| `PUT /api/v1/titles/{titleId}/crew` | Upsert `{ directors: [personId...], writers: [personId...] }` - a singleton per title, mirroring `title_crew` |

### 5.2 People

| Endpoint | Description |
|---|---|
| `POST /api/v1/people` | Create (`primaryName`, `birthYear`, `deathYear`, `primaryProfession`) |
| `PUT /api/v1/people/{personId}` | Full update |
| `PATCH /api/v1/people/{personId}` | Partial update |
| `DELETE /api/v1/people/{personId}` | Soft-delete |

### 5.3 Ratings (singleton per title)

| Endpoint | Description |
|---|---|
| `PUT /api/v1/titles/{titleId}/rating` | Set/replace `{ averageRating, numVotes }` |
| `DELETE /api/v1/titles/{titleId}/rating` | Remove the rating (title detail omits it) |

### 5.4 Cast/crew credits (`title_principals`)

| Endpoint | Auth | Description |
|---|---|---|
| `GET /api/v1/titles/{titleId}/principals` | Public | Full, uncapped credit list (title detail's own list stays capped at 20) |
| `POST /api/v1/titles/{titleId}/principals` | Admin | Add `{ personId, category, job, characters, ordering }` |
| `PUT /api/v1/titles/{titleId}/principals/{principalId}` | Admin | Update a credit |
| `DELETE /api/v1/titles/{titleId}/principals/{principalId}` | Admin | Soft-delete a credit |

Genres stay a plain validated string array on the title itself (matching the current schema), not a
separate managed `Genre` resource - a full taxonomy-management feature isn't justified by anything in
scope here.

Admin write endpoints are added to the *existing* `TitleController`/`PersonController`/`GenreController`
classes (`@PreAuthorize`-gated), not parallel `Admin*Controller` classes - the resource is the same, only
the allowed verb set differs by role.

## 6. Cross-Cutting Concerns

### 6.1 Optimistic locking

Every entity with a `PUT`/`PATCH` update path gets a `version` integer column, incremented on each
successful write. The current `version` must be included in the update request body; a mismatch is a
`409 Conflict` (new `domain.exception.ConflictException`), not a silent overwrite. This applies to:
`title_basics`, `name_basics`, `title_ratings`, `title_principals`, `title_crew`, `reviews`, `lists`,
`users` (profile updates). Join/item-only tables (`watchlist_items`, `list_items`) don't get a version -
they're pure add/remove, not update-in-place.

### 6.2 Cache invalidation on writes

The original caching design's 24h TTLs and "no write path, so `FLUSHDB` on redeploy is fine" reasoning
(LLD §6) no longer holds once writes exist. Fixed per cache region, using Spring's existing `@Cacheable`
decorator classes (`infrastructure.cache`) extended with `@CacheEvict` methods - no new custom eviction
infrastructure needed, since this is exactly what the annotation-driven cache abstraction already does:

| Cache region | Eviction on write |
|---|---|
| `title-detail` | Precise: `@CacheEvict(cacheNames = "title-detail", key = "#titleId")` on any title/rating/crew/principals/review write affecting that title |
| `title-search` | No precise key to evict (keyed by arbitrary `query:page:size` combos). TTL reduced from 24h to 15 minutes instead - the honest trade-off, since evicting "every search result that might now be stale" isn't feasible without scanning |
| `top-rated` | Coarse: `@CacheEvict(cacheNames = "top-rated", allEntries = true)` on any title/rating write - correct and cheap, since admin writes are expected to be infrequent |
| `six-degrees` | Coarse: `@CacheEvict(cacheNames = "six-degrees", allEntries = true)` on any people/principals write - a precise per-affected-pair eviction would need a reverse lookup this design doesn't build; full-region eviction favors correctness over cache efficiency for a rarely-written path |

### 6.3 Error handling additions

- `domain.exception.ConflictException` -> `409`: duplicate review, stale optimistic-lock version, duplicate
  email at registration.
- `domain.exception.ForbiddenException` -> `403`: non-owner attempting to modify someone else's
  watchlist/list/review.
- Both mapped in `ApiExceptionHandler` alongside the existing `NotFoundException`/`IllegalArgumentException`
  handlers - same pattern, no change to the `extends ResponseEntityExceptionHandler` approach that fixed
  the earlier 404/400/405 regression.
- Bean Validation (`jakarta.validation`, already a dependency) on every new request DTO.

### 6.4 Soft-delete convention

A `deleted_at TIMESTAMPTZ` column on every writable table (added in `V7` for the core tables, native on
every new table from `V5` onward). Every `SELECT` in `infrastructure.persistence` gains a
`WHERE deleted_at IS NULL` clause. A soft-deleted title's existing cast credits, reviews, and
watchlist/list items are left in place but the title itself stops appearing in search, detail, top-rated,
and six-degrees traversal - a watchlist/list item referencing a since-deleted title is filtered out of its
parent's item list rather than erroring.

### 6.5 Pagination and PATCH conventions

New collection endpoints (reviews, public lists, admin user list) reuse the existing hand-rolled
`PagedResult<T>` (LLD §4.1) - no new pagination mechanism. `PATCH` uses merge-patch semantics (only
included fields change; omitted fields are left alone), not full JSON Patch (RFC 6902) - consistent with
this project's plain-DTO style elsewhere.

## 7. Data Model / Schema Additions

New Flyway migrations, `V5` onward (current latest is `V4__title_principals_nconst_index.sql`):

| Migration | Adds |
|---|---|
| `V5__users_table.sql` | `users` (id, email, password_hash, display_name, bio, role, version, created_at, deleted_at) - schema only, no seed row (bootstrap admin handled by `BootstrapAdminRunner`, see §3) |
| `V6__admin_id_sequences.sql` | `title_id_seq` seeded to `MAX(tconst)+1`, `person_id_seq` seeded to `MAX(nconst)+1` |
| `V7__core_entity_version_and_soft_delete.sql` | `version`/`deleted_at` columns on `title_basics`, `name_basics`, `title_ratings`, `title_principals`, `title_crew` |
| `V8__watchlists.sql` | `watchlists` (id, user_id, visibility, version, deleted_at), `watchlist_items` (watchlist_id, title_id, added_at) |
| `V9__reviews.sql` | `reviews` (id, user_id, title_id, rating, body, version, deleted_at, created_at, updated_at); unique constraint on `(user_id, title_id)` where `deleted_at IS NULL` |
| `V10__lists.sql` | `lists` (id, user_id, name, visibility, version, deleted_at), `list_items` (list_id, title_id, added_at, ordering) |

## 8. Architecture Impact

New additions within the existing onion layering (LLD §2.1), no change to the dependency rule itself:

```
domain/
  model/          User, Role, Watchlist, WatchlistItem, Review, CustomList, ListItem, Visibility
  repository/      UserRepository, WatchlistRepository, ReviewRepository, ListRepository (interfaces)
  exception/       ConflictException, ForbiddenException
application/
  contracts/       AuthUseCase, UserUseCase, WatchlistUseCase, ReviewUseCase, ListUseCase,
                   TitleAdminUseCase, PersonAdminUseCase
  *Impl            plain orchestration for each, mirroring the existing use-case/decorator split
infrastructure/
  persistence/     JdbcUserRepository, JdbcWatchlistRepository, JdbcReviewRepository, JdbcListRepository
  security/        JwtService, JwtAuthenticationFilter, SecurityConfig,
                   ProblemDetailAuthenticationEntryPoint, ProblemDetailAccessDeniedHandler
  cache/           @CacheEvict additions to the existing Caching* decorators (§6.2) - no new decorator
                   classes, since eviction is added to the classes that already own each cache region
presentation/
  AuthController, UserController (new), WatchlistController, ReviewController, ListController
  TitleController, PersonController, GenreController - gain @PreAuthorize-gated write methods (existing
  classes, not new Admin* ones)
```

`TitleAdminUseCase` covers title CRUD + crew + rating + principals (all title-scoped admin operations,
grouped by domain cohesion rather than one interface per table); `PersonAdminUseCase` covers person CRUD.

## 9. Testing Plan Additions

- **Unit**: one test class per new use case (mocked repository interfaces), matching the existing pattern
  exactly. `JwtService` gets dedicated unit tests (issue/parse/expiry/tampered-signature rejection).
- **Integration** (Testcontainers): new JDBC repository integration tests for `users`/`watchlists`/
  `reviews`/`lists`, following the existing `*IntegrationTest.java` convention. New integration tests
  verifying `@CacheEvict` actually clears the affected Redis entry after a write - the same
  Redis-Testcontainers rationale as the existing four cache integration tests (LLD §10.2): a mocked cache
  can't prove an eviction call actually reached Redis.
- **E2E** (Postman/Newman): a full auth flow (register -> login -> use access token -> refresh), a full
  CRUD lifecycle per new resource (watchlist, review, list), and negative cases specifically worth
  contract-testing at this tier: a non-admin hitting an admin write endpoint (403), a stale-version update
  (409), and access to another user's private list (404).

## 10. Observability Additions

Per the existing level policy (LLD §7): INFO for login success/failure and every admin write (business-
significant, same tier as the existing six-degrees timing/ambiguity logs); DEBUG for cache-eviction calls
(mirrors the existing cache-miss DEBUG convention); WARN for repeated failed-login attempts from the same
account, as a lightweight brute-force signal without building a full account-lockout feature. No new
dashboard is planned in this pass - the existing HTTP Overview dashboard's per-`uri` breakdown already
covers the new endpoints automatically.

## 11. Out of Scope / Deferred

- **Rate limiting** - a genuinely separate, infra-level concern (normally a gateway/proxy responsibility,
  not application code); adding a Redis-backed token-bucket limiter now would meaningfully balloon this
  pass's scope without being core to "CRUD expansion." Documented here as a deliberate boundary, the same
  way the original PDD documented Pruned Landmark Labeling/Neo4j as evaluated-but-not-built.
- **Email verification / password reset flows** - registration creates a usable account immediately; a
  real "verify your email" or "forgot password" flow is a distinct feature or its own pass.
- **A separate `Genre` managed resource** - genres stay a plain validated string array (§5.4).
- **Bulk import/export endpoints** - every write endpoint here is single-resource; batch operations aren't
  part of this pass.
- **Full JSON Patch (RFC 6902)** - `PATCH` uses simple merge-patch semantics instead (§6.5).
