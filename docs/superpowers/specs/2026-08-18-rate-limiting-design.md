# Rate limiting — design

## Context

The backend deploys as a single Docker container on one EC2 instance (`.github/workflows/deploy.yml`),
bound directly to port 8080 with no reverse proxy, load balancer, or Redis/shared cache in front of
it (`docker run -p 8080:8080`). No API endpoint currently enforces any request-rate limit.

Goal: protect public/anonymous endpoints from abuse and spam (the primary driver — unbounded abuse
inflates backend cost), protect auth endpoints from brute-force/credential-stuffing, protect webhook
endpoints from being flooded, and put a general cost/availability ceiling on the rest of the API —
without adding new infrastructure (no Redis), since the deployment is single-instance.

## Approach

A single `RateLimitingFilter` (`OncePerRequestFilter`), registered
`addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class)` in `SecurityConfiguration` so it
runs immediately after JWT auth resolves `SecurityContext` (needed for user-id-keyed limits) but
before the request reaches Spring MVC / controllers.

An ordered list of rules — same style as the `authorizeHttpRequests` matcher list already in
`SecurityConfiguration` — maps `(HTTP method, path pattern) → tier config`. First match wins; a
catch-all `Default` tier applies to anything unmatched. Tier config is Java-defined (a small
`List<RateLimitRule>` built in a `RateLimitConfiguration` class), not YAML-externalized — this repo
already rebuilds and redeploys on every push, so externalizing the numeric knobs buys no real
flexibility. One `enunas.rate-limit.enabled` boolean property is added so the whole filter can be
killed via env var without a code change if it misbehaves in production.

Buckets are provided by `bucket4j-core` (in-memory token buckets — no Redis, matches the
single-instance deployment) and held in `ConcurrentHashMap<String, BucketHolder>`, where
`BucketHolder` wraps the `Bucket` plus a last-access timestamp. A `@Scheduled` cleanup task runs
every 10 minutes and evicts entries idle for more than 30 minutes, so the map doesn't grow unbounded
under scraping/scanning traffic.

Rejected: `bucket4j-spring-boot-starter` (third-party, less control over the IP-vs-user-id /
dual-key logic this design needs, and a YAML-declarative model that would fight rather than help
here) and a `HandlerInterceptor` (functionally equivalent to the filter approach but a worse fit
for this codebase's existing pattern of doing auth work as a `Filter`).

## Key resolution

- **IP-keyed tiers** use `request.getRemoteAddr()`. `X-Forwarded-For` is deliberately **not**
  trusted — there is no reverse proxy in front of the container, so trusting that header would let
  a client spoof its own rate-limit key.
- **Default tier** keys off the JWT subject (user id) when `SecurityContext` already holds an
  authenticated principal (the filter runs after `JwtAuthenticationFilter`); falls back to IP if the
  request is unauthenticated.
- **Dual-key tiers** (see below) additionally key off an `email` value extracted from the request,
  independent of the IP key.

## Tiers

Rules are checked in order below; first match wins.

| Tier | Routes | Primary key | Secondary key |
|---|---|---|---|
| Exempt | `/actuator/health`, `/error`, `OPTIONS *` (CORS preflight) | — | — |
| Auth-strict | `POST /auth/login`, `POST /auth/google` | IP, 5/min | — |
| Reset-confirm | `POST /auth/reset-password` | IP, 5/min | — |
| Reset-request | `POST /auth/forgot-password` | IP, 5/min | email, 3/60min |
| Signup/apply | `POST /auth/signup`, `POST /brandpartner/apply`, `POST /brandpartner/verify`, `POST /brandpartner/resend-verification` | IP, 5/min | email, 3/60min |
| Webhooks | `POST /webhooks/mollie`, `POST /webhooks/mock/**` | IP, 60/min | — |
| Product search | `GET /products/search` | IP, 10/min | — |
| Public catalog | `GET /products/**`, `GET /listings/**` | IP, 60/min | — |
| Default | everything else (incl. `/admin/**`, `/auth/set-password`, `/auth/change-password`) | user id if authenticated, else IP, 120/min | — |

Notes:
- `Product search` must be matched *before* `Public catalog` (`GET /products/search` is more
  specific than `GET /products/**`) — the ordered rule list already resolves this the same way
  `SecurityConfiguration` orders its more-specific matchers first. Search is the most DB-expensive
  read on the catalog (filtering/pagination vs. a single lookup), hence the tighter 10/min.
- `/admin/**` is not special-cased: it's already role-gated, and the trusted-staff traffic it
  carries (CSV/JSON §22f exports, settlement reports) fits comfortably inside the 120/min default.
- `reset-password`'s body is `{token, newPassword}` — no email field — so it stays IP-only; the
  single-use reset token is the real gate there, not an email-based limit.
- `resend-verification`'s `email` is a `@RequestParam` (query string), not a JSON body field.

## Dual-key mechanism

Applies only to the 5 routes marked with a secondary key above.

1. Primary bucket (IP) is checked first. If exhausted, deny immediately with an IP-derived
   `Retry-After`.
2. If the matched rule declares a secondary key, extract it and check a second, independent bucket.
   If exhausted, deny with an email-derived `Retry-After`. (The IP bucket may have already been
   decremented in this case — an accepted, low-severity trade-off of checking two independent
   dimensions.)
3. If secondary-key extraction fails (malformed JSON, missing/blank field), that check is skipped
   (fail-open on the secondary dimension only — the primary IP check has already run).

**Extraction:**
- For the 4 JSON-body routes (`/auth/signup`, `/auth/forgot-password`, `/brandpartner/apply`,
  `/brandpartner/verify`), the filter wraps the request in a `CachedBodyHttpServletRequestWrapper`
  (stores the body as a `byte[]`, replays it on every subsequent `getInputStream()`/`getReader()`
  call) **only when the matched rule needs it** — not globally. It parses the cached bytes as a
  Jackson `JsonNode` and reads the top-level `email` field. The wrapped request is passed down the
  filter chain unchanged, so the controller's `@RequestBody` binding still works normally.
- For `/brandpartner/resend-verification`, `email` is read directly via
  `request.getParameter("email")` — no body wrapping needed.

## Response format

On any bucket denial, the filter short-circuits the chain and writes:

- HTTP `429 Too Many Requests`
- `Retry-After: <seconds>` header
- JSON body, matching `GlobalExceptionHandler`'s existing error shape plus rate-limit-specific
  fields:

```json
{
  "timestamp": "2026-08-18T10:00:00.000",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again in 42 seconds.",
  "path": "/auth/login",
  "retryAfter": 42,
  "limit": 5,
  "remaining": 0,
  "resetAt": "2026-08-18T10:00:42.000"
}
```

This is written directly by the filter (it runs outside `DispatcherServlet`, so
`@RestControllerAdvice` doesn't apply) using the shared `ObjectMapper`.

## Out of scope

- Mollie webhook signature verification / IP allowlisting — a separate authenticity concern from
  rate limiting; the Webhooks tier here only guards against volume, not spoofing.
- Distributed/shared rate-limit state (Redis) — not needed at single-instance scale; would become
  relevant if the deployment moves to multiple instances behind a load balancer.
- Per-user configurable limits (e.g. higher ceiling for specific trusted brand partners) — YAGNI for
  v1.

## Testing

- Unit tests (no Spring context): rule matching/ordering, IP-vs-user-id key resolution, dual-key
  consume-and-deny logic, `Retry-After`/`resetAt` computation, `CachedBodyHttpServletRequestWrapper`
  replayability (body readable twice, once by the filter and once by `@RequestBody` binding).
- One `@SpringBootTest` (existing `test` + `mock-payments` profile infra) driving `POST /auth/login`
  past its limit end-to-end, asserting `429` + `Retry-After` + the JSON shape above, and confirming
  a `Default`-tier route still serves normally afterward (limits are isolated per key/tier).
- Manual note: buckets are in-memory and reset on app restart/redeploy — acceptable given the
  single-instance deployment.
