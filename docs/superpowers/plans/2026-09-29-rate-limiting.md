# Rate Limiting + 1s Micro-Cache Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Per-rule in-memory rate limiting on abuse-prone endpoints (plus a global cap), and a 1-second in-RAM response cache for anonymous public catalog GETs.

**Architecture:** Two plain `OncePerRequestFilter`s, built with `new` inside `SecurityConfiguration` (so they are never double-registered as servlet filters), chained `JwtAuthenticationFilter → RateLimitFilter → MicroCacheFilter → MVC`. State lives in `ConcurrentHashMap`s; time comes from an injected `java.time.Clock`; tunables bind from `app.rate-limit.*` / `app.micro-cache.*` via `@ConfigurationProperties` records.

**Tech Stack:** Spring Boot 4.0.5, Spring Security, Java records, JUnit 5 + AssertJ, `spring-test` mocks (`MockHttpServletRequest`/`Response`), Testcontainers integration base `AbstractDiscountIntegrationTest`.

**Spec:** `docs/superpowers/specs/2026-09-28-rate-limiting-design.md`

## Global Constraints

- **No new dependencies.** No Bucket4j, Caffeine, Redis. Everything uses the JDK and Spring already on the classpath.
- **Every implementer runs under `ponytail` (full).** Shortest working diff, reuse before writing. Include this in each implementer dispatch.
- **No git commits.** The user stages, commits and pushes themselves. Each task ends with the working tree left modified, not committed.
- **Never run `mvn clean`.** OneDrive file locks break it. Run tests with `./mvnw -q test -Dtest=<Class>` from `backend/`.
- Package for all new main classes: `com.enunas.backend.config`.
- Single instance; counters and cache reset on restart. That is accepted.
- The 429 body must match the API envelope `{timestamp, status, error, message, path}` and carry a `Retry-After` header (seconds, rounded up, minimum 1).
- Rule defaults, verbatim from the spec: login 10/1m · signup 5/1h · password-email 3/15m · password-change 10/15m · checkout 20/1m · returns 10/1h · media 60/1m · webhook 120/1m · global 300/1m.
- Micro-cache: `GET` only, paths `/products/**`, `/listings/**`, `/brands/**`, **no `Authorization` header**, status 200 only, TTL 1s, max 10 000 entries.
- `application-test.yaml` sets both features `enabled: false`; this feature's own tests re-enable them per class.

## Review Focus

1. **A map full of live entries must not turn every request into an O(n) scan.** Both filters sweep expired entries at most once per second. Pinned by `MicroCacheFilterTest.fullCache_skipsInsertButStillServes` (Task 3). The rate limiter uses the same throttled sweep (Task 1).
2. **Two logged-in users behind one NAT IP must not share a checkout budget.** Pinned by `RateLimitIntegrationTest.checkout_isKeyedPerUserNotPerIp` (Task 2).
3. **A client rotating `X-Forwarded-For` gets a fresh IP budget.** This is a deployment requirement (backend reachable only via the proxy), not code. It's documented in the spec, and `differentForwardedIp_isNotAffected` (Task 2) proves the header *is* honoured.
4. **Cached hits replay only status 200, the body and `Content-Type`.** Any other controller-set header is dropped on a hit. Today no catalog GET sets custom headers. If one ever does (ETag, Cache-Control), extend `Entry`.
5. **Integration tests share one app context, and with it one cache and one set of counters.** Tests use distinct `X-Forwarded-For` IPs and cache-busting query strings (`?t=<test>`). `RESTART IDENTITY` reuses product id 1 across tests, so without this a stale entry leaks between them.

---

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/enunas/backend/config/RateLimitProperties.java` (new) | Binds `app.rate-limit.*` |
| `backend/src/main/java/com/enunas/backend/config/RateLimitFilter.java` (new) | Rule matching, fixed-window counting, 429 |
| `backend/src/main/java/com/enunas/backend/config/MicroCacheProperties.java` (new) | Binds `app.micro-cache.*` |
| `backend/src/main/java/com/enunas/backend/config/MicroCacheFilter.java` (new) | Anonymous GET response cache |
| `backend/src/main/java/com/enunas/backend/config/ApplicationConfiguration.java` (modify) | Adds the `Clock` bean |
| `backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java` (modify) | Wires both filters after the JWT filter |
| `backend/src/main/resources/application.yaml` (modify) | Defaults + `server.forward-headers-strategy` |
| `backend/src/test/resources/application-test.yaml` (modify) | Disables both features |
| `backend/src/test/java/com/enunas/backend/config/RateLimitFilterTest.java` (new) | Unit tests |
| `backend/src/test/java/com/enunas/backend/config/MicroCacheFilterTest.java` (new) | Unit tests |
| `backend/src/test/java/com/enunas/backend/config/RateLimitIntegrationTest.java` (new) | End-to-end through the security chain |
| `backend/src/test/java/com/enunas/backend/config/MicroCacheIntegrationTest.java` (new) | End-to-end through the security chain |
| `backend/src/test/java/com/enunas/backend/config/MutableClock.java` (new) | Test clock shared by both unit tests |

---

### Task 1: RateLimitFilter core (unit-tested, not wired yet)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/RateLimitProperties.java`
- Create: `backend/src/main/java/com/enunas/backend/config/RateLimitFilter.java`
- Create: `backend/src/test/java/com/enunas/backend/config/MutableClock.java`
- Test: `backend/src/test/java/com/enunas/backend/config/RateLimitFilterTest.java`

**Interfaces:**
- Consumes: `com.enunas.backend.user.User` (principal type set by `JwtAuthenticationFilter`; `Long getId()`).
- Produces:
  - `public record RateLimitProperties(boolean enabled, int maxEntries, Map<String, Rule> rules)` with nested `public record Rule(int limit, Duration window)`, annotated `@ConfigurationProperties("app.rate-limit")`.
  - `public RateLimitFilter(RateLimitProperties props, Clock clock)`. The constructor throws `IllegalStateException` if any of the 9 rule names is missing.
  - Test helper `MutableClock` with `void advance(Duration d)`.

- [ ] **Step 1: Create the test clock**

```java
package com.enunas.backend.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test-only clock that only moves when told to. */
class MutableClock extends Clock {

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    void advance(Duration d) {
        now = now.plus(d);
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.enunas.backend.config;

import com.enunas.backend.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitFilterTest {

    private static final List<String> RULES = List.of("login", "signup", "password-email",
            "password-change", "checkout", "returns", "media", "webhook", "global");

    private final MutableClock clock = new MutableClock();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Everything 100/min except login=2, checkout=2, global=3, so limits are cheap to hit. */
    private static RateLimitProperties props(boolean enabled) {
        Map<String, RateLimitProperties.Rule> rules = new HashMap<>();
        RULES.forEach(n -> rules.put(n, new RateLimitProperties.Rule(100, Duration.ofMinutes(1))));
        rules.put("login", new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put("checkout", new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put("global", new RateLimitProperties.Rule(3, Duration.ofMinutes(1)));
        return new RateLimitProperties(enabled, 100_000, rules);
    }

    private static MockHttpServletResponse send(RateLimitFilter f, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static void loginAs(long userId) {
        User u = User.builder().id(userId).email("u" + userId + "@t.local").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(u, null, List.of()));
    }

    @Test
    void overLimit_returns429WithRetryAfterAndEnvelope() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        MockHttpServletResponse third = send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("60");
        assertThat(third.getContentType()).startsWith("application/json");
        assertThat(third.getContentAsString())
                .contains("\"status\":429", "\"error\":\"Too Many Requests\"",
                        "\"path\":\"/auth/login\"", "\"timestamp\":", "\"message\":");
    }

    @Test
    void retryAfter_roundsUpRemainingSeconds() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");
        clock.advance(Duration.ofMillis(59_500));

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void windowRollover_allowsAgain() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");
        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(1));

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void differentIps_haveSeparateBudgets() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(send(f, "POST", "/auth/login", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void userKeyedRule_separatesUsersOnSameIp() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        loginAs(1);
        send(f, "POST", "/orders", "1.1.1.1");
        send(f, "POST", "/orders/preview", "1.1.1.1");
        assertThat(send(f, "POST", "/orders", "1.1.1.1").getStatus()).isEqualTo(429);

        loginAs(2);
        assertThat(send(f, "POST", "/orders", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void unmatchedRequest_fallsIntoGlobalCap() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        send(f, "GET", "/products", "1.1.1.1");
        send(f, "GET", "/listings", "1.1.1.1");
        send(f, "GET", "/products/1", "1.1.1.1");

        assertThat(send(f, "GET", "/brands/1/public-profile", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void namedRule_doesNotConsumeGlobalBudget() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(send(f, "GET", "/products", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void healthCheck_isNeverLimited() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock);
        for (int i = 0; i < 10; i++) {
            assertThat(send(f, "GET", "/actuator/health", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void disabled_passesEverythingThrough() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(false), clock);
        for (int i = 0; i < 10; i++) {
            assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void missingRuleConfig_failsAtConstruction() {
        Map<String, RateLimitProperties.Rule> rules = new HashMap<>(props(true).rules());
        rules.remove("webhook");

        assertThatThrownBy(() -> new RateLimitFilter(new RateLimitProperties(true, 1, rules), clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.rate-limit.rules.webhook");
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run (from `backend/`): `./mvnw -q test -Dtest=RateLimitFilterTest`
Expected: compilation failure, `cannot find symbol: class RateLimitFilter` / `RateLimitProperties`.

- [ ] **Step 4: Write `RateLimitProperties`**

```java
package com.enunas.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/** Binds {@code app.rate-limit.*}. Which paths belong to which rule is code, see {@link RateLimitFilter}. */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(boolean enabled, int maxEntries, Map<String, Rule> rules) {

    public record Rule(int limit, Duration window) {}
}
```

- [ ] **Step 5: Write `RateLimitFilter`**

```java
package com.enunas.backend.config;

import com.enunas.backend.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window, in-memory rate limiting. Exactly one rule applies per request: the first matching
 * route, else "global". Runs after {@link JwtAuthenticationFilter} so user-keyed rules can see the
 * caller. Single-instance only: counters are per JVM (see the spec's Future section for Redis).
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private record Route(String name, HttpMethod method, List<String> patterns, boolean byUser) {}

    private record Window(long resetAt, int count) {}

    private static final String GLOBAL = "global";

    private static final List<Route> ROUTES = List.of(
            new Route("login", HttpMethod.POST, List.of("/auth/login", "/auth/google"), false),
            new Route("signup", HttpMethod.POST, List.of("/auth/signup"), false),
            new Route("password-email", HttpMethod.POST, List.of("/auth/forgot-password"), false),
            new Route("password-change", HttpMethod.POST,
                    List.of("/auth/reset-password", "/auth/change-password", "/auth/set-password"), false),
            new Route("checkout", HttpMethod.POST, List.of("/orders", "/orders/preview"), true),
            new Route("returns", HttpMethod.POST, List.of("/orders/*/return"), true),
            new Route("media", HttpMethod.POST, List.of("/products/*/media/**"), true),
            new Route("webhook", HttpMethod.POST, List.of("/webhooks/mollie"), false));

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties props;
    private final Clock clock;
    private volatile long lastSweep;

    public RateLimitFilter(RateLimitProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        // Fail at startup, not on the first request that needs a missing rule.
        ROUTES.forEach(r -> rule(r.name()));
        rule(GLOBAL);
    }

    private RateLimitProperties.Rule rule(String name) {
        RateLimitProperties.Rule rule = props.rules() == null ? null : props.rules().get(name);
        if (rule == null) {
            throw new IllegalStateException("app.rate-limit.rules." + name + " is not configured");
        }
        return rule;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !props.enabled() || "/actuator/health".equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        Route route = ROUTES.stream()
                .filter(r -> r.method().matches(request.getMethod())
                        && r.patterns().stream().anyMatch(p -> matcher.match(p, path)))
                .findFirst().orElse(null);
        String name = route == null ? GLOBAL : route.name();
        RateLimitProperties.Rule rule = rule(name);
        long now = clock.millis();

        sweepIfFull(now);
        Window w = windows.compute(name + ":" + subject(request, route == null || route.byUser()),
                (k, old) -> old == null || now >= old.resetAt()
                        ? new Window(now + rule.window().toMillis(), 1)
                        : new Window(old.resetAt(), old.count() + 1));

        if (w.count() <= rule.limit()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (w.resetAt() - now + 999) / 1000)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // path is the raw request URI; Tomcat rejects unencoded '"' and '\', so it needs no escaping.
        response.getWriter().write("{\"timestamp\":\"" + LocalDateTime.now(clock)
                + "\",\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Too many requests, please try again later.\",\"path\":\"" + path + "\"}");
    }

    private static String subject(HttpServletRequest request, boolean byUser) {
        if (byUser) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return "u:" + user.getId();
            }
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** At most one O(n) sweep per second, so a map full of live entries can't slow every request. */
    private void sweepIfFull(long now) {
        if (windows.size() >= props.maxEntries() && now - lastSweep >= 1000) {
            lastSweep = now;
            windows.values().removeIf(w -> now >= w.resetAt());
        }
    }
}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q test -Dtest=RateLimitFilterTest`
Expected: all 10 tests PASS. If `User.builder().id(...)` doesn't compile (no `id` on the builder), construct with `new User()` + `setId(...)` instead. Check `User.java` for Lombok annotations.

- [ ] **Step 7: Stop.** Leave the changes uncommitted; the user commits.

---

### Task 2: Wire RateLimitFilter into the app + integration test

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/config/ApplicationConfiguration.java` (add `Clock` bean)
- Modify: `backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java`
- Modify: `backend/src/main/resources/application.yaml` (`server:` block and `app:` block, near line 64 and line 118)
- Modify: `backend/src/test/resources/application-test.yaml` (`app:` block at the end)
- Test: `backend/src/test/java/com/enunas/backend/config/RateLimitIntegrationTest.java`

**Interfaces:**
- Consumes: `RateLimitProperties`, `RateLimitFilter(RateLimitProperties, Clock)` from Task 1; `AbstractDiscountIntegrationTest` (`rest`, `seedUser`, `customerRepository`, `seedCustomer()`) from `com.enunas.backend.discount.integration`.
- Produces: a `java.time.Clock` bean (`Clock.systemUTC()`), used by Task 4. `SecurityConfiguration` gains a `Clock clock` field that Task 4 reuses.

- [ ] **Step 1: Write the failing integration test**

```java
package com.enunas.backend.config;

import com.enunas.backend.customer.Customer;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real security chain. Counters live for the whole (shared) context, so every test
 * uses its own X-Forwarded-For IP (honoured via server.forward-headers-strategy=framework).
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.rules.checkout.limit=2",
        "app.rate-limit.rules.global.limit=3"
})
class RateLimitIntegrationTest extends AbstractDiscountIntegrationTest {

    private HttpHeaders from(String ip) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String loginFrom(String ip, String email, String password) {
        return (String) post("/auth/login", Map.of("email", email, "password", password), from(ip))
                .getBody().get("token");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void login_eleventhAttemptFromSameIp_is429WithRetryAfter() {
        Map<String, String> bad = Map.of("email", "nobody@it.local", "password", "wrong");
        for (int i = 0; i < 10; i++) {
            assertThat(post("/auth/login", bad, from("203.0.113.1")).getStatusCode().value()).isNotEqualTo(429);
        }

        ResponseEntity<Map> blocked = post("/auth/login", bad, from("203.0.113.1"));

        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(blocked.getBody()).containsEntry("status", 429).containsEntry("path", "/auth/login");
    }

    @Test
    void differentForwardedIp_isNotAffected() {
        Map<String, String> bad = Map.of("email", "nobody@it.local", "password", "wrong");
        for (int i = 0; i < 11; i++) {
            post("/auth/login", bad, from("203.0.113.2"));
        }

        assertThat(post("/auth/login", bad, from("203.0.113.3")).getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void checkout_isKeyedPerUserNotPerIp() {
        seedCustomer();
        User second = seedUser("second@it.local", "Customer123!", Role.CUSTOMER);
        customerRepository.save(Customer.builder().user(second).build());
        String a = loginFrom("198.51.100.1", "customer@it.local", "Customer123!");
        String b = loginFrom("198.51.100.2", "second@it.local", "Customer123!");

        HttpHeaders asA = from("198.51.100.7");
        asA.setBearerAuth(a);
        HttpHeaders asB = from("198.51.100.7");
        asB.setBearerAuth(b);

        // "{}" is an invalid preview body (400), but the limiter counts it before the controller runs.
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isNotEqualTo(429);
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isNotEqualTo(429);
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isEqualTo(429);

        assertThat(post("/orders/preview", "{}", asB).getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void anonymousCatalogReads_hitGlobalCap() {
        for (int i = 0; i < 3; i++) {
            assertThat(rest.exchange("/products", HttpMethod.GET, new HttpEntity<>(from("203.0.113.9")), String.class)
                    .getStatusCode().value()).isNotEqualTo(429);
        }

        assertThat(rest.exchange("/products", HttpMethod.GET, new HttpEntity<>(from("203.0.113.9")), String.class)
                .getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void healthCheck_isNeverLimited() {
        for (int i = 0; i < 6; i++) {
            assertThat(rest.exchange("/actuator/health", HttpMethod.GET,
                    new HttpEntity<>(from("203.0.113.10")), String.class).getStatusCode().value()).isNotEqualTo(429);
        }
    }
}
```

Note: check `Customer`'s real package (`grep -rn "class Customer " backend/src/main/java`) and fix the import if it differs. `AbstractDiscountIntegrationTest` already imports it.

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=RateLimitIntegrationTest`
Expected: FAIL. `login_eleventhAttempt…`, `checkout_isKeyed…` and `anonymousCatalogReads…` see no 429 because the filter isn't wired yet. The other two may pass.

- [ ] **Step 3: Add the `Clock` bean** to `ApplicationConfiguration` (add `import java.time.Clock;`):

```java
    /** UTC, matching the JVM pin in TimezoneConfig. Injected so time-dependent filters are testable. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
```

- [ ] **Step 4: Wire the filter in `SecurityConfiguration`**

Annotate the class with `@EnableConfigurationProperties(RateLimitProperties.class)` (import `org.springframework.boot.context.properties.EnableConfigurationProperties`). Add fields and constructor params:

```java
    private final RateLimitProperties rateLimitProperties;
    private final Clock clock;

    public SecurityConfiguration(
            AuthenticationProvider authenticationProvider,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver,
            RateLimitProperties rateLimitProperties,
            Clock clock
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.handlerExceptionResolver = handlerExceptionResolver;
        this.rateLimitProperties = rateLimitProperties;
        this.clock = clock;
    }
```

Replace the last line of the chain:

```java
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Built with `new`, not @Component: a Filter bean would also be auto-registered as a
                // plain servlet filter and every request would be counted twice.
                .addFilterAfter(new RateLimitFilter(rateLimitProperties, clock), JwtAuthenticationFilter.class);
```

- [ ] **Step 5: Add config defaults to `application.yaml`**

Under the existing top-level `server:` block (next to `port: 8080`):

```yaml
  # The backend sits behind a reverse proxy: take the client IP from X-Forwarded-For.
  # Requires that the port is reachable ONLY via the proxy (otherwise the header is spoofable).
  forward-headers-strategy: framework
```

Under the existing top-level `app:` block:

```yaml
  rate-limit:
    enabled: true
    max-entries: 100000
    rules:
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

- [ ] **Step 6: Disable in the test profile**, appended to the `app:` block of `application-test.yaml`:

```yaml
  # Off for the suite (it logs in / checks out far more than any real user); the feature's own
  # tests turn it back on via @TestPropertySource.
  rate-limit:
    enabled: false
```

- [ ] **Step 7: Run the new test, then the whole suite**

Run: `./mvnw -q test -Dtest=RateLimitIntegrationTest`
Expected: 5 tests PASS.

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, with no new failures compared to before this task. A mass `NoClassDefFoundError` means OneDrive lock interference, not a regression. Re-run without `clean`.

- [ ] **Step 8: Stop.** Leave the changes uncommitted.

---

### Task 3: MicroCacheFilter core (unit-tested, not wired yet)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/MicroCacheProperties.java`
- Create: `backend/src/main/java/com/enunas/backend/config/MicroCacheFilter.java`
- Test: `backend/src/test/java/com/enunas/backend/config/MicroCacheFilterTest.java`

**Interfaces:**
- Consumes: `MutableClock` (test helper from Task 1).
- Produces:
  - `@ConfigurationProperties("app.micro-cache") public record MicroCacheProperties(boolean enabled, Duration ttl, int maxEntries)`.
  - `public MicroCacheFilter(MicroCacheProperties props, Clock clock)`.

- [ ] **Step 1: Write the failing tests**

```java
package com.enunas.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MicroCacheFilterTest {

    private final MutableClock clock = new MutableClock();
    private final AtomicInteger calls = new AtomicInteger();
    private int status = 200;

    /** Stands in for the controller: counts invocations and answers {"n":<call number>}. */
    private final FilterChain controller = (req, res) -> {
        int n = calls.incrementAndGet();
        ((HttpServletResponse) res).setStatus(status);
        res.setContentType("application/json");
        res.getWriter().write("{\"n\":" + n + "}");
    };

    private MicroCacheFilter filter(boolean enabled, int maxEntries) {
        return new MicroCacheFilter(new MicroCacheProperties(enabled, Duration.ofSeconds(1), maxEntries), clock);
    }

    private MockHttpServletResponse get(MicroCacheFilter f, String uri, String query, String authorization)
            throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setQueryString(query);
        if (authorization != null) req.addHeader("Authorization", authorization);
        MockHttpServletResponse res = new MockHttpServletResponse();
        f.doFilter(req, res, controller);
        return res;
    }

    @Test
    void anonymousGet_withinTtl_isServedFromCache() throws Exception {
        MicroCacheFilter f = filter(true, 100);

        MockHttpServletResponse first = get(f, "/products/1", null, null);
        MockHttpServletResponse second = get(f, "/products/1", null, null);

        assertThat(calls).hasValue(1);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(second.getContentType()).startsWith("application/json");
        assertThat(second.getContentAsString()).isEqualTo(first.getContentAsString()).isEqualTo("{\"n\":1}");
    }

    @Test
    void afterTtl_reachesControllerAgain() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/products/1", null, null);

        clock.advance(Duration.ofMillis(1001));

        assertThat(get(f, "/products/1", null, null).getContentAsString()).isEqualTo("{\"n\":2}");
    }

    @Test
    void authorizationHeader_bypassesCache() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/products/1", null, "Bearer x");
        get(f, "/products/1", null, "Bearer x");

        assertThat(calls).hasValue(2);
    }

    @Test
    void anonymousHit_isNotServedFromAnAuthenticatedFill() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/products/1", null, "Bearer x");

        assertThat(get(f, "/products/1", null, null).getContentAsString()).isEqualTo("{\"n\":2}");
    }

    @Test
    void non200_isNotCached() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        status = 404;
        get(f, "/products/999", null, null);
        get(f, "/products/999", null, null);

        assertThat(calls).hasValue(2);
    }

    @Test
    void queryString_isPartOfKey() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/products", "page=0", null);
        get(f, "/products", "page=1", null);
        get(f, "/products", "page=0", null);

        assertThat(calls).hasValue(2);
    }

    @Test
    void nonCatalogPath_isNotCached() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/orders/me", null, null);
        get(f, "/orders/me", null, null);

        assertThat(calls).hasValue(2);
    }

    @Test
    void post_isNotCached() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/products/create");
            f.doFilter(req, new MockHttpServletResponse(), controller);
        }

        assertThat(calls).hasValue(2);
    }

    @Test
    void fullCache_skipsInsertButStillServes() throws Exception {
        MicroCacheFilter f = filter(true, 1);
        get(f, "/products/1", null, null);                 // fills the only slot

        assertThat(get(f, "/products/2", null, null).getContentAsString()).isEqualTo("{\"n\":2}");
        assertThat(get(f, "/products/2", null, null).getContentAsString()).isEqualTo("{\"n\":3}");
        assertThat(get(f, "/products/1", null, null).getContentAsString()).isEqualTo("{\"n\":1}");
    }

    @Test
    void disabled_passesThrough() throws Exception {
        MicroCacheFilter f = filter(false, 100);
        get(f, "/products/1", null, null);
        get(f, "/products/1", null, null);

        assertThat(calls).hasValue(2);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=MicroCacheFilterTest`
Expected: compilation failure, `cannot find symbol: class MicroCacheFilter`.

- [ ] **Step 3: Write `MicroCacheProperties`**

```java
package com.enunas.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code app.micro-cache.*}. */
@ConfigurationProperties("app.micro-cache")
public record MicroCacheProperties(boolean enabled, Duration ttl, int maxEntries) {}
```

- [ ] **Step 4: Write `MicroCacheFilter`**

```java
package com.enunas.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-TTL cache for anonymous public catalog GETs, so bursts of identical requests skip the
 * database. Anonymous-only is load-bearing: product/listing detail endpoints answer per viewer.
 * Replace with nginx/CDN caching once one is in front (then set app.micro-cache.enabled=false).
 */
public class MicroCacheFilter extends OncePerRequestFilter {

    private record Entry(byte[] body, String contentType, long expiresAt) {}

    private static final List<String> PATHS = List.of("/products/**", "/listings/**", "/brands/**");

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final MicroCacheProperties props;
    private final Clock clock;
    private volatile long lastSweep;

    public MicroCacheFilter(MicroCacheProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !props.enabled()
                || !"GET".equals(request.getMethod())
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || PATHS.stream().noneMatch(p -> matcher.match(p, request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String key = request.getQueryString() == null
                ? request.getRequestURI()
                : request.getRequestURI() + "?" + request.getQueryString();
        long now = clock.millis();

        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType(hit.contentType());
            response.setContentLength(hit.body().length);
            response.getOutputStream().write(hit.body());
            return;
        }

        ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, wrapper);
            if (wrapper.getStatus() == HttpServletResponse.SC_OK && hasRoom(now)) {
                cache.put(key, new Entry(wrapper.getContentAsByteArray(), wrapper.getContentType(),
                        now + props.ttl().toMillis()));
            }
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    /** Past the cap, sweep expired entries at most once per second; if still full, don't insert. */
    private boolean hasRoom(long now) {
        if (cache.size() >= props.maxEntries() && now - lastSweep >= 1000) {
            lastSweep = now;
            cache.values().removeIf(e -> e.expiresAt() <= now);
        }
        return cache.size() < props.maxEntries();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./mvnw -q test -Dtest=MicroCacheFilterTest`
Expected: all 10 tests PASS.

- [ ] **Step 6: Stop.** Leave the changes uncommitted.

---

### Task 4: Wire MicroCacheFilter into the app + integration test

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java`
- Modify: `backend/src/main/resources/application.yaml` (`app:` block)
- Modify: `backend/src/test/resources/application-test.yaml` (`app:` block)
- Test: `backend/src/test/java/com/enunas/backend/config/MicroCacheIntegrationTest.java`

**Interfaces:**
- Consumes: `MicroCacheProperties`, `MicroCacheFilter(MicroCacheProperties, Clock)` (Task 3); the `clock` field and the `RateLimitFilter` registration in `SecurityConfiguration` (Task 2); `AbstractDiscountIntegrationTest` (`seedBrand`, `seedListing`, `login`, `auth`, `jdbc`, `rest`).
- Produces: nothing downstream.

- [ ] **Step 1: Write the failing integration test**

```java
package com.enunas.backend.config;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TTL is 1h so nothing expires mid-test. The cache outlives each test's TRUNCATE ... RESTART IDENTITY
 * (product id 1 recurs), so every test uses its own ?t= query string to get a private cache key.
 */
@TestPropertySource(properties = {"app.micro-cache.enabled=true", "app.micro-cache.ttl=1h"})
class MicroCacheIntegrationTest extends AbstractDiscountIntegrationTest {

    private long seedProduct() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.15");
        long listingId = seedListing(brand.brand(), brand.user(), "20.00", 5);
        return jdbc.queryForObject("SELECT product_id FROM listings WHERE id = ?", Long.class, listingId);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void anonymousRead_isServedFromCache_authenticatedReadIsNot() {
        long productId = seedProduct();
        String url = "/products/" + productId + "?t=cache";

        String original = (String) rest.getForObject(url, Map.class).get("name");
        jdbc.update("UPDATE products SET name = 'Renamed' WHERE id = ?", productId);

        assertThat(rest.getForObject(url, Map.class).get("name")).isEqualTo(original);

        String token = login("acme@it.local", "Brand123!");
        Map fresh = rest.exchange(url, HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();
        assertThat(fresh.get("name")).isEqualTo("Renamed");
    }

    @Test
    void notFound_isNotCached() {
        long productId = seedProduct();
        String url = "/products/" + productId + "?t=404";
        jdbc.update("UPDATE listings SET active = false WHERE product_id = ?", productId);

        assertThat(rest.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(404);

        jdbc.update("UPDATE listings SET active = true WHERE product_id = ?", productId);
        assertThat(rest.getForEntity(url, String.class).getStatusCode().value()).isEqualTo(200);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./mvnw -q test -Dtest=MicroCacheIntegrationTest`
Expected: `anonymousRead_isServedFromCache…` FAILS (the second anonymous read returns `Renamed`). `notFound_isNotCached` passes already, which is fine because it guards against a regression.

- [ ] **Step 3: Wire the filter in `SecurityConfiguration`**

Extend the annotation to `@EnableConfigurationProperties({RateLimitProperties.class, MicroCacheProperties.class})`. Add a `private final MicroCacheProperties microCacheProperties;` field and a constructor parameter (after `RateLimitProperties rateLimitProperties`), and assign it. Then append after the rate-limit line:

```java
                .addFilterAfter(new RateLimitFilter(rateLimitProperties, clock), JwtAuthenticationFilter.class)
                // After the limiter so cache hits still count against the caller's budget.
                .addFilterAfter(new MicroCacheFilter(microCacheProperties, clock), RateLimitFilter.class);
```

- [ ] **Step 4: Config defaults** in `application.yaml` under `app:`:

```yaml
  # 1s anonymous catalog cache. Turn off once nginx/a CDN caches in front (see rate-limiting spec).
  micro-cache:
    enabled: true
    ttl: 1s
    max-entries: 10000
```

And in `application-test.yaml` under `app:`:

```yaml
  # Off so tests that mutate then re-read a product anonymously never see stale data.
  micro-cache:
    enabled: false
```

- [ ] **Step 5: Run the new test, then the whole suite**

Run: `./mvnw -q test -Dtest=MicroCacheIntegrationTest`
Expected: 2 tests PASS.

Run: `./mvnw -q test`
Expected: BUILD SUCCESS, with no new failures compared to before Task 1.

- [ ] **Step 6: Stop.** Leave the changes uncommitted. Hand over to the review layers in CLAUDE.md step 4.
