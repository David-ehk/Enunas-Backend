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
  `X-Forwarded-For`, resolved by Tomcat (`server.forward-headers-strategy: native`,
  i.e. `RemoteIpValve`). It walks the header right-to-left and only trusts hops
  inside `server.tomcat.remoteip.internal-proxies` (default: private ranges
  10/8, 172.16/12, 192.168/16, 127/8, ::1, …), so a client cannot pick its own
  IP by sending a leftmost value. `X-Forwarded-Prefix` is ignored.
  *(Changed from `framework` after the final review: `framework` trusts the
  leftmost, client-supplied entry, which appending proxies such as nginx's
  `$proxy_add_x_forwarded_for` pass straight through.)*
  Deployment requirements, not code: the proxy's address must be inside
  `internal-proxies` (set it explicitly if the proxy has a public address), and
  the backend port must not be publicly reachable. Note `docker-compose.yml`
  currently publishes `8080:8080`.
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
   `ip:<request.getRemoteAddr()>` -- except an IPv6 address, which is keyed by its `/64` prefix
   (`ip6:<first 8 bytes as hex>`) instead of the full address, so a client that rotates through its
   own `/64` cannot dodge the budget. IPv4 is unaffected.
3. `map.compute(key, …)`: if absent or `now - windowStart >= window`, start a
   new window with count 1; else increment.
4. If `count > limit`, respond 429 (below) and do **not** call the chain.

Time comes from an injected `java.time.Clock` so tests can advance it.

**Memory bound, no scheduler:** `map.values().removeIf(expired)` runs at most
once per second, on every request (not gated on the map being full -- same
policy as the micro-cache's sweep below, so a low-traffic instance doesn't
keep stale windows on the heap indefinitely). If, after that, `map.size()` is
still at `app.rate-limit.max-entries` (default 100 000) and the key is new,
the request passes **untracked** (fail-open: failing closed would let an
attacker lock out every new visitor); existing keys keep counting. No
`@Scheduled` sweep needed.

Rules are a `RateLimitRule` enum (one constant per row of the table below,
`GLOBAL` last so it always matches as a fallback), each carrying its method,
path patterns, and whether it's keyed by user. `RateLimitProperties.rules` is
`Map<RateLimitRule, Rule>`; the yaml keys stay kebab-case (`login`,
`password-email`, …) via Spring's relaxed enum-map-key binding. Rule
completeness (every enum constant must be configured) is still checked at
filter construction, so a missing `app.rate-limit.rules.<name>` fails
startup with that name in the message. Value validity (`limit`/`max-entries`
positive, `window` present and positive) is Bean Validation on
`RateLimitProperties` itself (`@Positive`, `@NotNull` + `@DurationMin`), not a
hand-written check. Each 429 is logged at DEBUG with the rule's config key and
subject key.

### Rules (defaults)

Matching uses `AntPathMatcher` on the **decoded** path within the application
(`UrlPathHelper.getPathWithinApplication`), plus the HTTP method, so
percent-encoded spellings (`/auth/%6Cogin`) cannot escape their rule. The raw
URI is only echoed in the 429 body. Evaluated in this order:

| # | Rule | Method + paths | Key | Limit |
|---|---|---|---|---|
| 1 | `login` | `POST /auth/login`, `/auth/google` | IP | 10 / 1 min |
| 2 | `signup` | `POST /auth/signup`, `/brandpartner/apply` | IP | 5 / 1 h |
| 3 | `password-email` | `POST /auth/forgot-password`, `/brandpartner/resend-verification` | IP | 3 / 15 min |
| 4 | `password-change` | `POST /auth/reset-password`, `/auth/change-password`, `/auth/set-password`, `/brandpartner/verify` | IP | 10 / 15 min |
| 5 | `checkout` | `POST /orders`, `/orders/preview` | user, else IP | 20 / 1 min |
| 6 | `returns` | `POST /orders/*/return` | user, else IP | 10 / 1 h |
| 7 | `media` | `POST /products/*/media/**`, `/brandpartner/media/upload-url` | user, else IP | 60 / 1 min |
| 8 | `webhook` | `POST /webhooks/mollie` | IP | 1000 / 1 min |
| 9 | `global` | any other request | user, else IP | 300 / 1 min |

Notes:

- The public brand-partner endpoints (`apply`, `resend-verification`, `verify`) are `permitAll`
  and would otherwise fall into the far looser `global` cap; `verify`'s 6-digit code has no
  attempt counter of its own, so it rides `password-change` (code guessing) while `apply`/
  `resend-verification` ride the rules for the mail they send.
- `webhook` is a flood guard only. Mollie posts from a small shared IP pool, and a busy drop can
  push well over 120 status changes/min through it; 429ing those delays a paid webhook into the
  late-payment auto-refund path, so the limit is set far above any legitimate single-IP burst.
  Mollie retries failed deliveries regardless, so a 429 here just means a delayed retry.
- `/actuator/health` is **exempt** (no rule, not counted), so a health checker
  polling it is never throttled.
- Limits and windows are configurable (below); the rule list itself (which
  paths belong to which rule -- a `RateLimitRule` enum) is code, not config.
  YAGNI on a config DSL.

### 429 response

`Retry-After` is set directly on the response (headers must be set before the
response is committed), then the filter routes through the same
`HandlerExceptionResolver` the JWT filter and the security entry points
already use -- `resolver.resolveException(request, response, null,
new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later."))`
-- so `GlobalExceptionHandler` stays the single definition of the error
envelope instead of the filter hand-writing a second copy of it:

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
  forward-headers-strategy: native

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
      webhook:         { limit: 1000, window: 1m }
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
Key: decoded in-application path (`UrlPathHelper.getPathWithinApplication`,
same as `RateLimitFilter`) + `"?" + queryString` (query omitted when null), so
a percent-encoded spelling shares the same entry as its decoded form instead
of fragmenting the cache.

```
entry = cache.get(key)
if (entry != null && entry.expires().isAfter(clock.instant())) {
    write entry.body with entry.contentType, status 200; return
}
wrapper = new ContentCachingResponseWrapper(response)
chain.doFilter(request, wrapper)
if (wrapper.getStatus() == 200 && body.length <= MAX_BODY_BYTES) cache.put(key, new Entry(body, contentType, now + ttl))
wrapper.copyBodyToResponse()
```

A single response over `MAX_BODY_BYTES` (256 KiB, hard-coded -- not config) is
never cached; it's still served normally, just not stored.

**Memory bound:** sweep expired entries (`removeIf(expired)`) at most once per
second, on *every* insert attempt -- not only once `cache.size() >=
max-entries` (default 10 000). Gating the sweep on being full meant a
low-traffic instance that never reached max-entries could keep stale, expired
bodies on the heap indefinitely. If still full after sweeping, skip inserting.
No eviction policy beyond that, and no scheduler. `ttl` and `max-entries` are
Bean-Validated on `MicroCacheProperties` (`@NotNull` + `@DurationMin`,
`@Positive`), same mechanism as the rate limiter's rule validation -- not a
hand-written constructor check.

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
2. A different IP (via `X-Forwarded-For` from 127.0.0.1, a trusted internal proxy under the native
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
  (`app.rate-limit`, with `Map<RateLimitRule, Rule(int limit, Duration window)>`,
  both Bean-Validated) and `MicroCacheProperties` (`app.micro-cache`, also
  Bean-Validated)
- `config/RateLimitRule.java` (new): one enum constant per rule, in
  match-priority order
- `application.yaml`, `application-test.yaml`
- A new `Clock` bean (`Clock.systemUTC()`); none exists today. The JVM is
  already pinned to UTC per `project_utc_clock_convention`.
- Tests listed above

## Future (not in scope)

### Cache layering: one layer per job

A cache only saves the work that happens *after* it in the request path:

```
Browser → CDN → nginx → Spring app (RAM) → Redis → Postgres
  cheapest hit, least control  ←——→  most control, most expensive hit
```

- **nginx / CDN replaces the micro-cache; it doesn't sit on top of it.** Both
  are short-TTL anonymous response caches, and nginx's hits never reach the
  JVM. Stacking them adds staleness (1s + 1s) and a second place to debug for
  almost no extra hits. When nginx or a CDN is in front, set
  `app.micro-cache.enabled: false`.
- nginx must keep the **anonymous-only** rule
  (`proxy_cache_bypass $http_authorization; proxy_no_cache $http_authorization;`),
  or it will serve per-viewer responses (e.g. a brand's view of its own Coming
  Soon product) to everyone.
- A CDN is driven by the app's `Cache-Control` headers: `public, max-age=…` on
  anonymous catalog responses, `private, no-store` on anything tied to a
  logged-in user.
- **Rate limiting may legitimately be layered:** a coarse per-IP `limit_req`
  at nginx as a flood guard, plus the app's rules that need app knowledge
  (per-user checkout etc.).

### Redis: when and what for

Redis is further from the client than process RAM, so it does not make HTTP
responses faster. Its value is **state shared across instances** plus
**data-level caching with precise invalidation**.

| Use | Why Redis | Trigger to adopt |
|---|---|---|
| Rate-limit counters | Otherwise each replica is a fresh budget for an attacker | > 1 instance |
| Distributed lock for `@Scheduled` jobs (`PayoutGenerationScheduler`, `PayoutReleaseService`, `OrderExpiryService`) | On > 1 instance every job runs on every replica: **duplicate payout generation/release is a money bug**. ShedLock works; its Postgres provider avoids needing Redis just for this | > 1 instance (**blocker, not optional**) |
| `@Cacheable` / `@CacheEvict` data cache | Caches data by meaning, can be evicted exactly on write, can hold per-user data safely. nginx caches bytes by URL and can only wait out a TTL | A query *measured* to be hot and slow (brand profiles, category listings, settlement aggregates) |
| Idempotency keys, JWT denylist, one-time codes | Native per-key TTL, no cleanup jobs | When the feature is needed |
| Durable queue for e-mail listeners | Today mail is sent in-process; a crash mid-send loses the mail. Redis Streams / RabbitMQ / SQS survive restarts | When lost mail becomes a real problem |

Scaling path:

| Stage | Response cache | Rate-limit state |
|---|---|---|
| Now (1 instance) | In-app 1s micro-cache | In-app RAM |
| nginx / CDN in front | nginx / CDN; micro-cache **off** | In-app RAM (+ optional nginx `limit_req`) |
| > 1 instance | nginx / CDN | Redis (and a job lock, see above) |

Swapping the limiter to Redis (or Bucket4j + Redis) changes only the store;
the filter's public behaviour (rules, 429, `Retry-After`) stays the same.
