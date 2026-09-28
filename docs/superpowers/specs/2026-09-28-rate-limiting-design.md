# Rate limiting + 1s micro-cache — design

**Date:** 2026-09-28
**Status:** Draft, awaiting review
**Classification:** production (cross-cutting, security chain)

## Goal

Stop abuse of the endpoints that can be brute-forced, spammed, or used to burn
resources (auth, password e-mails, checkout / Mollie payment creation, media
presigning, the public webhook), and put a generous overall cap on everything
else so one client cannot flood the API. A normal shopper never sees a limit.

Alongside it, a 1-second in-RAM response cache for anonymous public catalog
reads, so bursts of identical requests are served from memory and one instance
can carry more traffic, without introducing Redis yet.

### Constraints (agreed)

- **Single backend instance** in production → all state lives in process
  memory. Counters and cache reset on restart; acceptable.
- **Behind a reverse proxy / PaaS** → the client IP comes from
  `X-Forwarded-For`, resolved by Spring (`server.forward-headers-strategy: framework`).
  That strategy trusts the header from any sender, so the backend port must be
  reachable **only** through the proxy. If it is exposed directly, a client can
  send a fresh `X-Forwarded-For` on every request and dodge IP-keyed limits.
  This is a deployment requirement, not code.
- **No new dependencies.** No Bucket4j, no Caffeine, no Redis.

### Non-goals

- Multi-instance / shared counters. If the backend is ever scaled out, swap the
  store for Redis or Bucket4j (see *Future*). Not now.
- Proxy-level limits (nginx `limit_req`, Cloudflare). Complementary, can be
  added on top later, out of scope here.
- Caching authenticated responses, or any cache invalidation.

## Part 1 — Rate limiting

### Component

`config/RateLimitFilter` extends `OncePerRequestFilter`.

- Registered **in the Spring Security chain** with
  `addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)`, so it can
  read the authenticated user from `SecurityContextHolder`.
- **Not** auto-registered as a plain servlet filter too: either it is not a
  `@Component` (constructed in `SecurityConfiguration`), or a
  `FilterRegistrationBean` with `setEnabled(false)` is declared. Otherwise it
  runs twice and every request counts double.

### Algorithm: fixed window

State: `ConcurrentHashMap<String, Window>`, where
`record Window(long windowStartMillis, int count)`.

For each request:

1. Find the **first** matching rule (ordered list, see table). Exactly one rule
   applies per request. Named rules *replace* the global cap; they do not stack.
2. Build the key: `ruleName + ":" + subject`, where subject is
   `u:<userId>` for rules keyed by user when authenticated, otherwise
   `ip:<request.getRemoteAddr()>`.
3. `map.compute(key, …)`: if absent or `now - windowStart >= window`, start a
   new window with count 1; else increment.
4. If `count > limit`, respond 429 (below) and do **not** call the chain.

Time comes from an injected `java.time.Clock` so tests can advance it.

**Memory bound, no scheduler:** when `map.size()` exceeds
`app.rate-limit.max-entries` (default 100 000), run
`map.values().removeIf(expired)` before inserting. No `@Scheduled` sweep needed.

### Rules (defaults)

Matching uses `AntPathMatcher` on `request.getRequestURI()` minus the context
path, plus the HTTP method. Evaluated in this order:

| # | Rule | Method + paths | Key | Limit |
|---|---|---|---|---|
| 1 | `login` | `POST /auth/login`, `/auth/google` | IP | 10 / 1 min |
| 2 | `signup` | `POST /auth/signup` | IP | 5 / 1 h |
| 3 | `password-email` | `POST /auth/forgot-password` | IP | 3 / 15 min |
| 4 | `password-change` | `POST /auth/reset-password`, `/auth/change-password`, `/auth/set-password` | IP | 10 / 15 min |
| 5 | `checkout` | `POST /orders`, `/orders/preview` | user, else IP | 20 / 1 min |
| 6 | `returns` | `POST /orders/*/return` | user, else IP | 10 / 1 h |
| 7 | `media` | `POST /products/*/media/**` | user, else IP | 60 / 1 min |
| 8 | `webhook` | `POST /webhooks/mollie` | IP | 120 / 1 min |
| 9 | `global` | any other request | user, else IP | 300 / 1 min |

Notes:

- `webhook` is a flood guard only. Mollie retries failed deliveries, so 120/min
  from Mollie's IPs is far above anything legitimate traffic produces; a 429
  there just means Mollie retries later.
- `/actuator/health` is **exempt** (no rule, not counted), so a health checker
  polling it is never throttled.
- Limits and windows are configurable (below); the rule list itself (which
  paths belong to which rule) is code, not config. YAGNI on a config DSL.

### 429 response

Written directly by the filter (filters run before MVC, so
`GlobalExceptionHandler` is not involved), matching the API's error envelope:

```
HTTP/1.1 429 Too Many Requests
Retry-After: <seconds until the window resets, rounded up, min 1>
Content-Type: application/json

{"timestamp": "...", "status": 429, "error": "Too Many Requests",
 "message": "Too many requests, please try again later.", "path": "/auth/login"}
```

No `ErrorCode` constant: the status 429 plus `Retry-After` is already the
machine-readable contract.

### Configuration

`application.yaml`:

```yaml
server:
  forward-headers-strategy: framework

app:
  rate-limit:
    enabled: true
    max-entries: 100000
    rules:            # only limit + window are tunable, per rule name
      login:           { limit: 10,  window: 1m }
      signup:          { limit: 5,   window: 1h }
      password-email:  { limit: 3,   window: 15m }
      password-change: { limit: 10,  window: 15m }
      checkout:        { limit: 20,  window: 1m }
      returns:         { limit: 10,  window: 1h }
      media:           { limit: 60,  window: 1m }
      webhook:         { limit: 120, window: 1m }
      global:          { limit: 300, window: 1m }
```

`application-test.yaml`: `app.rate-limit.enabled: false`, so the existing
integration suites (which log in and check out many times) are unaffected.
Tests for this feature turn it back on per class.

When `enabled` is false the filter is a pass-through.

## Part 2 — 1s micro-cache

### Component

`config/MicroCacheFilter` extends `OncePerRequestFilter`, registered in the
security chain **after** `RateLimitFilter`, with the same double-registration
guard.

Order: `JwtAuthenticationFilter` → `RateLimitFilter` → `MicroCacheFilter` →
MVC. A cache hit still counts against the caller's rate limit, so the cache
cannot be used to bypass limits. CORS headers are added earlier in the chain,
so hits still carry them.

### What is cached

A request is cacheable only if **all** hold:

- method is `GET`;
- path matches `/products/**`, `/listings/**`, or `/brands/**`;
- **no `Authorization` header**, i.e. anonymous.

The anonymous-only rule is load-bearing: `GET /products/{id}`, `/sku/{sku}`,
`/slug/{slug}`, `/products/{id}/listings` and `/listings/{id}` take
`@AuthenticationPrincipal User viewer` and can answer differently per viewer
(e.g. a brand viewing its own Coming Soon product). Caching only anonymous
requests means one user's response can never be served to another.
Authenticated GETs, including `/products/my`, always hit the controller.

Only responses with status **200** are stored.

### Mechanism

State: `ConcurrentHashMap<String, Entry>`, where
`record Entry(byte[] body, String contentType, Instant expires)`.
Key: `requestURI + "?" + queryString` (query omitted when null).

```
entry = cache.get(key)
if (entry != null && entry.expires().isAfter(clock.instant())) {
    write entry.body with entry.contentType, status 200; return
}
wrapper = new ContentCachingResponseWrapper(response)
chain.doFilter(request, wrapper)
if (wrapper.getStatus() == 200) cache.put(key, new Entry(body, contentType, now + ttl))
wrapper.copyBodyToResponse()
```

**Memory bound:** when `cache.size() >= max-entries` (default 10 000), first
`removeIf(expired)`; if still full, skip inserting. No eviction policy beyond
that, and no scheduler.

**Stampede:** deliberately not handled. When an entry expires, concurrent
misses on the same key each hit the database once. With a 1s TTL that is a
handful of queries, not a pile-up.

**Staleness:** at most `ttl` (1s). Stock and price are re-validated at checkout;
a Coming Soon product going live appears at most 1s late. Accepted.

### Configuration

```yaml
app:
  micro-cache:
    enabled: true
    ttl: 1s
    max-entries: 10000
```

`application-test.yaml`: `app.micro-cache.enabled: false`, so integration tests
that mutate a product and immediately re-read it anonymously never see stale
data.

## Testing

TDD, integration tests on the existing Testcontainers setup (see
`reference_integration_test_infra`), with the feature re-enabled per class via
`@TestPropertySource`.

**Rate limiting** (`RateLimitIntegrationTest`):

1. 10 × `POST /auth/login` from one IP → none are 429; the 11th → 429 with
   `Retry-After` ≥ 1 and the JSON envelope (`status`, `error`, `path`).
2. A different IP (via `X-Forwarded-For`, trusted under the framework
   strategy) is not affected by (1).
3. Authenticated user: the `checkout` bucket is keyed by user. Two users behind
   the same IP each get their own 20/min.
4. `/actuator/health` is never limited.
5. `enabled: false` → no 429 ever (covered implicitly by the rest of the suite
   still passing).

**Window rollover** (`RateLimitFilterTest`, plain unit test with a mutable
`Clock` and `MockHttpServletRequest`/`Response`): exhaust a limit → 429;
advance the clock past the window → allowed again. Expired entries are removed
once `max-entries` is exceeded.

**Micro-cache** (`MicroCacheIntegrationTest`, `@MockitoBean` on the product
service to count calls, or a spy):

1. Two anonymous `GET /products/{id}` within the TTL → service invoked once;
   identical bodies.
2. After the TTL (mutable `Clock`) → service invoked again.
3. Authenticated `GET /products/{id}` → never served from cache (service
   invoked every time).
4. 404 is not cached (a second request reaches the controller again).
5. `POST` / non-listed paths are never cached.

## Files touched

- `config/RateLimitFilter.java` (new)
- `config/MicroCacheFilter.java` (new)
- `config/SecurityConfiguration.java`: wire both filters after the JWT filter
- Two `@ConfigurationProperties` records, `RateLimitProperties`
  (`app.rate-limit`, with `Map<String, Rule(int limit, Duration window)>`) and
  `MicroCacheProperties` (`app.micro-cache`)
- `application.yaml`, `application-test.yaml`
- A new `Clock` bean (`Clock.systemUTC()`); none exists today. The JVM is
  already pinned to UTC per `project_utc_clock_convention`.
- Tests listed above

## Future (not in scope)

- Scaling beyond one instance: replace both maps with Redis (or Bucket4j +
  Redis for the limiter). The filters' public behaviour stays the same; only
  the store changes.
- Proxy-level limits as an outer layer once the production proxy is fixed.
