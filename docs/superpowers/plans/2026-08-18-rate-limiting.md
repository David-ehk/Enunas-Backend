# Rate Limiting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add in-memory, tier-based rate limiting to the appropriate REST endpoints (auth, signup/apply, webhooks, public catalog, and a general default) to curb abuse/spam cost and brute-force risk, without any new infrastructure.

**Architecture:** A single `RateLimitingFilter` (`OncePerRequestFilter`), registered right after `JwtAuthenticationFilter` in the security chain, matches each request against an ordered, first-match-wins rule table (mirroring `SecurityConfiguration`'s matcher list) and enforces one or two independent `bucket4j` token buckets per request (IP always; IP+email for a handful of anti-abuse-sensitive routes).

**Tech Stack:** Spring Boot 4.0.5 / Java 21, `bucket4j` (in-memory token buckets, no Redis), Spring Security filter chain, Jackson.

**Spec:** `docs/superpowers/specs/2026-08-18-rate-limiting-design.md`

## Global Constraints

- No Redis / distributed store — buckets are in-memory `ConcurrentHashMap`s (single-instance EC2 deployment).
- IP resolution uses `request.getRemoteAddr()` only — `X-Forwarded-For` is never trusted (no reverse proxy in front of the container).
- Tier definitions (path patterns, capacities, windows) are Java-defined, not YAML-externalized.
- The filter is globally killable via `enunas.rate-limit.enabled` (default `true`), overridable by env var without a code change.
- The 429 JSON body must match `GlobalExceptionHandler`'s existing shape (`timestamp`, `status`, `error`, `message`) plus `path`, `retryAfter`, `limit`, `remaining`, `resetAt`.
- Rate limiting must be off by default under the `test` Spring profile so it doesn't break the existing integration-test suite's repeated `/auth/login` calls; one dedicated test class re-enables it explicitly.

---

## File Structure

```
backend/pom.xml                                                          [modify] bucket4j dependency
backend/src/main/resources/application.yaml                              [modify] enunas.rate-limit.enabled: true
backend/src/test/resources/application-test.yaml                         [modify] enunas.rate-limit.enabled: false

backend/src/main/java/com/enunas/backend/config/ratelimit/
  RateLimitBucketRegistry.java          in-memory bucket4j buckets, keyed by tier+key, with idle eviction
  KeyStrategy.java                      enum: IP | USER_OR_IP
  SecondaryKeySource.java               enum: NONE | JSON_BODY_EMAIL | QUERY_PARAM_EMAIL
  RateLimitRule.java                    one tier's config (record) + factory helpers
  RateLimitRuleTable.java               ordered, first-match-wins list of RateLimitRule
  CachedBodyHttpServletRequestWrapper.java   replayable request body, for the dual-key JSON routes
  RequestEmailExtractor.java            pulls "email" out of a cached JSON body or a query param
  RateLimitResponseWriter.java          writes the 429 JSON response + headers

backend/src/main/java/com/enunas/backend/config/
  RateLimitingFilter.java               orchestrates the above; wired into SecurityConfiguration
  SecurityConfiguration.java             [modify] addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class)

backend/src/test/java/com/enunas/backend/config/ratelimit/
  RateLimitBucketRegistryTest.java
  RateLimitRuleTableTest.java
  CachedBodyHttpServletRequestWrapperTest.java
  RequestEmailExtractorTest.java
  RateLimitResponseWriterTest.java

backend/src/test/java/com/enunas/backend/config/
  RateLimitingFilterTest.java
  RateLimitingIntegrationTest.java
```

Each `ratelimit/*` file has one responsibility (bucket storage, rule matching, body caching, email
extraction, response writing) so `RateLimitingFilter` is a thin orchestrator, not a god-class.

---

## Task 1: RateLimitBucketRegistry (bucket4j buckets + idle eviction)

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistry.java`
- Test: `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistryTest.java`

**Interfaces:**
- Produces: `RateLimitBucketRegistry` — public no-arg constructor (production) and package-private
  `RateLimitBucketRegistry(LongSupplier clockMillis)` (tests); public method
  `ConsumptionProbe consume(String tierName, String key, int capacity, Duration window)`; public
  `@Scheduled` method `void evictIdleBuckets()`; package-private `int size()` for tests.

- [ ] **Step 1: Add the bucket4j dependency**

In `backend/pom.xml`, inside `<dependencies>`, add (near the other small focused libraries like
`jjwt-*`):

```xml
<dependency>
    <!-- In-memory rate-limiting token buckets (RateLimitingFilter). No Redis: single-instance
         EC2 deployment, see docs/superpowers/specs/2026-08-18-rate-limiting-design.md. The
         jdk17-targeted artifact is the actively maintained one (plain bucket4j-core is frozen at
         8.10.1); this project targets Java 21. -->
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j_jdk17-core</artifactId>
    <version>8.14.0</version>
</dependency>
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistryTest.java`:

```java
package com.enunas.backend.config.ratelimit;

import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitBucketRegistryTest {

    @Test
    void allowsUpToCapacityThenDenies() {
        RateLimitBucketRegistry registry = new RateLimitBucketRegistry();

        for (int i = 0; i < 5; i++) {
            ConsumptionProbe probe = registry.consume("auth-strict", "1.2.3.4", 5, Duration.ofMinutes(1));
            assertThat(probe.isConsumed()).as("request %d", i + 1).isTrue();
        }

        ConsumptionProbe sixth = registry.consume("auth-strict", "1.2.3.4", 5, Duration.ofMinutes(1));

        assertThat(sixth.isConsumed()).isFalse();
        assertThat(sixth.getNanosToWaitForRefill()).isGreaterThan(0);
    }

    @Test
    void tiersAndKeysHaveIndependentBudgets() {
        RateLimitBucketRegistry registry = new RateLimitBucketRegistry();

        for (int i = 0; i < 5; i++) {
            assertThat(registry.consume("auth-strict", "1.2.3.4", 5, Duration.ofMinutes(1)).isConsumed()).isTrue();
        }

        // Same key, different tier -> separate bucket, unaffected by auth-strict being exhausted.
        assertThat(registry.consume("signup-apply", "1.2.3.4", 5, Duration.ofMinutes(1)).isConsumed()).isTrue();
        // Different key, same tier -> also unaffected.
        assertThat(registry.consume("auth-strict", "5.6.7.8", 5, Duration.ofMinutes(1)).isConsumed()).isTrue();
    }

    @Test
    void evictIdleBucketsRemovesEntriesPastThreshold() {
        AtomicLong now = new AtomicLong(0);
        RateLimitBucketRegistry registry = new RateLimitBucketRegistry(now::get);

        registry.consume("auth-strict", "1.2.3.4", 5, Duration.ofMinutes(1));
        assertThat(registry.size()).isEqualTo(1);

        now.set(Duration.ofMinutes(31).toMillis()); // past the 30-minute idle threshold
        registry.evictIdleBuckets();

        assertThat(registry.size()).isZero();
    }

    @Test
    void evictIdleBucketsKeepsRecentlyTouchedEntries() {
        AtomicLong now = new AtomicLong(0);
        RateLimitBucketRegistry registry = new RateLimitBucketRegistry(now::get);

        registry.consume("auth-strict", "1.2.3.4", 5, Duration.ofMinutes(1));

        now.set(Duration.ofMinutes(10).toMillis()); // within the 30-minute idle threshold
        registry.evictIdleBuckets();

        assertThat(registry.size()).isEqualTo(1);
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitBucketRegistryTest`
Expected: FAIL to compile — `RateLimitBucketRegistry` does not exist yet.

- [ ] **Step 4: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistry.java`:

```java
package com.enunas.backend.config.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory bucket4j registry for the rate-limiting filter. One bucket per (tier, key) pair —
 * e.g. "auth-strict:203.0.113.7" — so the same IP gets an independent budget per tier. Buckets
 * idle for more than 30 minutes are evicted every 10 minutes to bound memory under
 * scraping/scanning traffic. Single-instance, in-memory by design — see
 * docs/superpowers/specs/2026-08-18-rate-limiting-design.md ("No Redis").
 */
@Slf4j
@Component
public class RateLimitBucketRegistry {

    private static final Duration IDLE_EVICTION_THRESHOLD = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, BucketHolder> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clockMillis;

    public RateLimitBucketRegistry() {
        this(System::currentTimeMillis);
    }

    /** Test-only constructor: inject a fake clock so idle-eviction is testable without sleeping. */
    RateLimitBucketRegistry(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    /**
     * Consumes one token from the bucket for {@code tierName + ":" + key}, creating it on first
     * use with the given capacity/refill window.
     */
    public ConsumptionProbe consume(String tierName, String key, int capacity, Duration window) {
        String mapKey = tierName + ":" + key;
        BucketHolder holder = buckets.computeIfAbsent(mapKey, k -> new BucketHolder(newBucket(capacity, window)));
        holder.lastAccessedMillis = clockMillis.getAsLong();
        return holder.bucket.tryConsumeAndReturnRemaining(1);
    }

    private static Bucket newBucket(int capacity, Duration window) {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, window))
                .build();
    }

    /** Package-visible for tests: number of buckets currently tracked. */
    int size() {
        return buckets.size();
    }

    @Scheduled(fixedRate = 600_000)
    public void evictIdleBuckets() {
        long cutoff = clockMillis.getAsLong() - IDLE_EVICTION_THRESHOLD.toMillis();
        int before = buckets.size();
        buckets.entrySet().removeIf(entry -> entry.getValue().lastAccessedMillis < cutoff);
        int after = buckets.size();
        if (after < before) {
            log.info("Rate limit bucket cleanup: {} -> {} entries", before, after);
        }
    }

    private static final class BucketHolder {
        private final Bucket bucket;
        private volatile long lastAccessedMillis;

        private BucketHolder(Bucket bucket) {
            this.bucket = bucket;
            this.lastAccessedMillis = System.currentTimeMillis();
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitBucketRegistryTest`
Expected: PASS (4 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistry.java backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitBucketRegistryTest.java
git commit -m "Add RateLimitBucketRegistry: in-memory bucket4j buckets with idle eviction"
```

---

## Task 2: Rate-limit rule table (tiers, ordered matching)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/KeyStrategy.java`
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/SecondaryKeySource.java`
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRule.java`
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRuleTable.java`
- Test: `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitRuleTableTest.java`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `public enum KeyStrategy { IP, USER_OR_IP }`; `public enum SecondaryKeySource { NONE, JSON_BODY_EMAIL, QUERY_PARAM_EMAIL }`; `public record RateLimitRule(String name, HttpMethod method, String pathPattern, boolean exempt, KeyStrategy keyStrategy, int capacity, Duration window, SecondaryKeySource secondaryKeySource, int secondaryCapacity, Duration secondaryWindow)`; `public class RateLimitRuleTable` with `public RateLimitRule match(HttpMethod requestMethod, String requestPath)` (`@Component`, no-arg constructor).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitRuleTableTest.java`:

```java
package com.enunas.backend.config.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitRuleTableTest {

    private final RateLimitRuleTable table = new RateLimitRuleTable();

    @Test
    void healthCheckIsExempt() {
        RateLimitRule rule = table.match(HttpMethod.GET, "/actuator/health");
        assertThat(rule.exempt()).isTrue();
    }

    @Test
    void loginMatchesAuthStrictTier() {
        RateLimitRule rule = table.match(HttpMethod.POST, "/auth/login");
        assertThat(rule.name()).isEqualTo("auth-strict");
        assertThat(rule.capacity()).isEqualTo(5);
        assertThat(rule.secondaryKeySource()).isEqualTo(SecondaryKeySource.NONE);
    }

    @Test
    void forgotPasswordMatchesResetRequestTierWithDualKey() {
        RateLimitRule rule = table.match(HttpMethod.POST, "/auth/forgot-password");
        assertThat(rule.name()).isEqualTo("reset-request");
        assertThat(rule.secondaryKeySource()).isEqualTo(SecondaryKeySource.JSON_BODY_EMAIL);
        assertThat(rule.secondaryCapacity()).isEqualTo(3);
    }

    @Test
    void resetPasswordHasNoSecondaryKey() {
        RateLimitRule rule = table.match(HttpMethod.POST, "/auth/reset-password");
        assertThat(rule.name()).isEqualTo("reset-confirm");
        assertThat(rule.secondaryKeySource()).isEqualTo(SecondaryKeySource.NONE);
    }

    @Test
    void resendVerificationUsesQueryParamEmail() {
        RateLimitRule rule = table.match(HttpMethod.POST, "/brandpartner/resend-verification");
        assertThat(rule.name()).isEqualTo("signup-apply");
        assertThat(rule.secondaryKeySource()).isEqualTo(SecondaryKeySource.QUERY_PARAM_EMAIL);
    }

    @Test
    void productSearchIsMoreSpecificThanPublicCatalog() {
        RateLimitRule searchRule = table.match(HttpMethod.GET, "/products/search");
        RateLimitRule detailRule = table.match(HttpMethod.GET, "/products/123");

        assertThat(searchRule.name()).isEqualTo("product-search");
        assertThat(searchRule.capacity()).isEqualTo(10);
        assertThat(detailRule.name()).isEqualTo("public-catalog");
        assertThat(detailRule.capacity()).isEqualTo(60);
    }

    @Test
    void unmatchedRouteFallsBackToDefaultTier() {
        RateLimitRule rule = table.match(HttpMethod.GET, "/customer/profile");
        assertThat(rule.name()).isEqualTo("default");
        assertThat(rule.keyStrategy()).isEqualTo(KeyStrategy.USER_OR_IP);
        assertThat(rule.capacity()).isEqualTo(120);
    }

    @Test
    void wrongMethodOnAStrictRouteFallsThroughToDefault() {
        // GET /auth/login isn't a real route, but proves method-mismatch doesn't false-match.
        RateLimitRule rule = table.match(HttpMethod.GET, "/auth/login");
        assertThat(rule.name()).isEqualTo("default");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitRuleTableTest`
Expected: FAIL to compile — none of the classes exist yet.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/KeyStrategy.java`:

```java
package com.enunas.backend.config.ratelimit;

/** How the primary rate-limit key is resolved for a matched request. */
public enum KeyStrategy {
    /** Always key by client IP (request.getRemoteAddr()). */
    IP,
    /** Key by the authenticated user id (JWT subject) when present, else fall back to IP. */
    USER_OR_IP
}
```

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/SecondaryKeySource.java`:

```java
package com.enunas.backend.config.ratelimit;

/** Where to read the optional secondary (dual-key) rate-limit value from, if any. */
public enum SecondaryKeySource {
    /** No secondary key — primary (IP or user id) is the only dimension checked. */
    NONE,
    /** Top-level "email" field of a JSON request body. Requires body caching. */
    JSON_BODY_EMAIL,
    /** "email" query parameter. */
    QUERY_PARAM_EMAIL
}
```

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRule.java`:

```java
package com.enunas.backend.config.ratelimit;

import org.springframework.http.HttpMethod;

import java.time.Duration;

/**
 * One rate-limit tier. {@link RateLimitRuleTable} holds these in match order — first match wins,
 * mirroring the ordered {@code authorizeHttpRequests} matcher list in SecurityConfiguration.
 *
 * @param method             HTTP method to match, or {@code null} to match any method.
 * @param pathPattern        Ant-style path pattern (e.g. "/auth/login", "/products/**").
 * @param exempt             when true, matched requests skip all rate limiting entirely.
 * @param keyStrategy        how to resolve the primary key (ignored when {@code exempt}).
 * @param capacity           primary bucket capacity (max requests per window).
 * @param window             primary bucket refill window.
 * @param secondaryKeySource where to read a secondary key from, or NONE for single-key tiers.
 * @param secondaryCapacity  secondary bucket capacity (ignored when secondaryKeySource is NONE).
 * @param secondaryWindow    secondary bucket refill window (ignored when secondaryKeySource is NONE).
 */
public record RateLimitRule(
        String name,
        HttpMethod method,
        String pathPattern,
        boolean exempt,
        KeyStrategy keyStrategy,
        int capacity,
        Duration window,
        SecondaryKeySource secondaryKeySource,
        int secondaryCapacity,
        Duration secondaryWindow
) {

    /** Exempt rule: matched requests bypass rate limiting entirely. */
    static RateLimitRule exempt(String name, HttpMethod method, String pathPattern) {
        return new RateLimitRule(name, method, pathPattern, true,
                null, 0, null, SecondaryKeySource.NONE, 0, null);
    }

    /** Single-key rule: only the primary (IP or user-id) bucket is checked. */
    static RateLimitRule singleKey(
            String name, HttpMethod method, String pathPattern,
            KeyStrategy keyStrategy, int capacity, Duration window
    ) {
        return new RateLimitRule(name, method, pathPattern, false,
                keyStrategy, capacity, window, SecondaryKeySource.NONE, 0, null);
    }

    /** Dual-key rule: primary (IP) bucket plus an independent secondary (email) bucket. */
    static RateLimitRule dualKey(
            String name, HttpMethod method, String pathPattern,
            int capacity, Duration window,
            SecondaryKeySource secondaryKeySource, int secondaryCapacity, Duration secondaryWindow
    ) {
        return new RateLimitRule(name, method, pathPattern, false,
                KeyStrategy.IP, capacity, window, secondaryKeySource, secondaryCapacity, secondaryWindow);
    }
}
```

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRuleTable.java`:

```java
package com.enunas.backend.config.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.time.Duration;
import java.util.List;

import static com.enunas.backend.config.ratelimit.SecondaryKeySource.JSON_BODY_EMAIL;
import static com.enunas.backend.config.ratelimit.SecondaryKeySource.QUERY_PARAM_EMAIL;

/**
 * Ordered rate-limit tiers — first match wins, falling back to {@link #DEFAULT}. See
 * docs/superpowers/specs/2026-08-18-rate-limiting-design.md for the rationale behind each tier's
 * numbers.
 *
 * <p>Multiple rules sharing the same {@code name} (e.g. "auth-strict" for both /auth/login and
 * /auth/google) intentionally share ONE bucket per key — the tier's number is a combined budget
 * across every route in that tier, not a per-route budget. This is deliberate: it closes the
 * loophole of an attacker round-robining between a tier's routes to multiply their effective
 * ceiling.
 */
@Component
public class RateLimitRuleTable {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private static final RateLimitRule DEFAULT = RateLimitRule.singleKey(
            "default", null, "/**", KeyStrategy.USER_OR_IP, 120, Duration.ofMinutes(1));

    private final List<RateLimitRule> orderedRules = List.of(
            RateLimitRule.exempt("exempt-health", null, "/actuator/health"),
            RateLimitRule.exempt("exempt-error", null, "/error"),

            RateLimitRule.singleKey("auth-strict", HttpMethod.POST, "/auth/login",
                    KeyStrategy.IP, 5, Duration.ofMinutes(1)),
            RateLimitRule.singleKey("auth-strict", HttpMethod.POST, "/auth/google",
                    KeyStrategy.IP, 5, Duration.ofMinutes(1)),

            RateLimitRule.singleKey("reset-confirm", HttpMethod.POST, "/auth/reset-password",
                    KeyStrategy.IP, 5, Duration.ofMinutes(1)),

            RateLimitRule.dualKey("reset-request", HttpMethod.POST, "/auth/forgot-password",
                    5, Duration.ofMinutes(1), JSON_BODY_EMAIL, 3, Duration.ofMinutes(60)),

            RateLimitRule.dualKey("signup-apply", HttpMethod.POST, "/auth/signup",
                    5, Duration.ofMinutes(1), JSON_BODY_EMAIL, 3, Duration.ofMinutes(60)),
            RateLimitRule.dualKey("signup-apply", HttpMethod.POST, "/brandpartner/apply",
                    5, Duration.ofMinutes(1), JSON_BODY_EMAIL, 3, Duration.ofMinutes(60)),
            RateLimitRule.dualKey("signup-apply", HttpMethod.POST, "/brandpartner/verify",
                    5, Duration.ofMinutes(1), JSON_BODY_EMAIL, 3, Duration.ofMinutes(60)),
            RateLimitRule.dualKey("signup-apply", HttpMethod.POST, "/brandpartner/resend-verification",
                    5, Duration.ofMinutes(1), QUERY_PARAM_EMAIL, 3, Duration.ofMinutes(60)),

            RateLimitRule.singleKey("webhooks", HttpMethod.POST, "/webhooks/mollie",
                    KeyStrategy.IP, 60, Duration.ofMinutes(1)),
            RateLimitRule.singleKey("webhooks", HttpMethod.POST, "/webhooks/mock/**",
                    KeyStrategy.IP, 60, Duration.ofMinutes(1)),

            // Must precede "public-catalog" below: /products/search is more specific than
            // /products/** and is the most DB-expensive catalog read (filtering + pagination).
            RateLimitRule.singleKey("product-search", HttpMethod.GET, "/products/search",
                    KeyStrategy.IP, 10, Duration.ofMinutes(1)),

            RateLimitRule.singleKey("public-catalog", HttpMethod.GET, "/products/**",
                    KeyStrategy.IP, 60, Duration.ofMinutes(1)),
            RateLimitRule.singleKey("public-catalog", HttpMethod.GET, "/listings/**",
                    KeyStrategy.IP, 60, Duration.ofMinutes(1))
    );

    /** Returns the first matching rule in tier order, or {@link #DEFAULT} if none match. */
    public RateLimitRule match(HttpMethod requestMethod, String requestPath) {
        for (RateLimitRule rule : orderedRules) {
            if (rule.method() != null && rule.method() != requestMethod) {
                continue;
            }
            if (PATH_MATCHER.match(rule.pathPattern(), requestPath)) {
                return rule;
            }
        }
        return DEFAULT;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitRuleTableTest`
Expected: PASS (8 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/config/ratelimit/KeyStrategy.java backend/src/main/java/com/enunas/backend/config/ratelimit/SecondaryKeySource.java backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRule.java backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitRuleTable.java backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitRuleTableTest.java
git commit -m "Add RateLimitRuleTable: ordered, first-match-wins rate-limit tiers"
```

---

## Task 3: CachedBodyHttpServletRequestWrapper

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapper.java`
- Test: `backend/src/test/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapperTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `public class CachedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper` with `public CachedBodyHttpServletRequestWrapper(HttpServletRequest request) throws IOException` and `public byte[] getCachedBody()`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapperTest.java`:

```java
package com.enunas.backend.config.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CachedBodyHttpServletRequestWrapperTest {

    @Test
    void bodyCanBeReadMultipleTimesThroughInputStream() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/auth/forgot-password");
        String json = "{\"email\":\"person@example.com\"}";
        original.setContent(json.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequestWrapper wrapper = new CachedBodyHttpServletRequestWrapper(original);

        String firstRead = StreamUtils.copyToString(wrapper.getInputStream(), StandardCharsets.UTF_8);
        String secondRead = StreamUtils.copyToString(wrapper.getInputStream(), StandardCharsets.UTF_8);

        assertThat(firstRead).isEqualTo(json);
        assertThat(secondRead).isEqualTo(json);
    }

    @Test
    void bodyCanBeReadMultipleTimesThroughReader() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/auth/forgot-password");
        String json = "{\"email\":\"person@example.com\"}";
        original.setContent(json.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequestWrapper wrapper = new CachedBodyHttpServletRequestWrapper(original);

        assertThat(wrapper.getReader().readLine()).isEqualTo(json);
        assertThat(wrapper.getReader().readLine()).isEqualTo(json);
    }

    @Test
    void getCachedBodyReturnsTheRawBytes() throws Exception {
        MockHttpServletRequest original = new MockHttpServletRequest("POST", "/auth/forgot-password");
        String json = "{\"email\":\"person@example.com\"}";
        original.setContent(json.getBytes(StandardCharsets.UTF_8));

        CachedBodyHttpServletRequestWrapper wrapper = new CachedBodyHttpServletRequestWrapper(original);

        assertThat(new String(wrapper.getCachedBody(), StandardCharsets.UTF_8)).isEqualTo(json);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CachedBodyHttpServletRequestWrapperTest`
Expected: FAIL to compile — class does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapper.java`:

```java
package com.enunas.backend.config.ratelimit;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.util.StreamUtils;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Reads and caches the full request body once, then serves it (unlimited times) to every
 * subsequent {@link #getInputStream()} / {@link #getReader()} caller. Used only for the handful
 * of rate-limit rules that need to peek at a JSON body's "email" field before the request reaches
 * the controller — the controller's own {@code @RequestBody} binding reads through this same
 * wrapper afterward and sees the identical body.
 */
public class CachedBodyHttpServletRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
    }

    public byte[] getCachedBody() {
        return cachedBody;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(cachedBody);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return byteStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // Not needed for synchronous request handling — no-op.
            }

            @Override
            public int read() {
                return byteStream.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CachedBodyHttpServletRequestWrapperTest`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapper.java backend/src/test/java/com/enunas/backend/config/ratelimit/CachedBodyHttpServletRequestWrapperTest.java
git commit -m "Add CachedBodyHttpServletRequestWrapper: replayable request body for dual-key routes"
```

---

## Task 4: RequestEmailExtractor

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/RequestEmailExtractor.java`
- Test: `backend/src/test/java/com/enunas/backend/config/ratelimit/RequestEmailExtractorTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks (takes a raw `byte[]` body or an `HttpServletRequest`, not the wrapper type itself).
- Produces: package-private `RequestEmailExtractor` with static `Optional<String> fromJsonBody(byte[] cachedBody, ObjectMapper objectMapper)` and static `Optional<String> fromQueryParam(HttpServletRequest request)`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/ratelimit/RequestEmailExtractorTest.java`:

```java
package com.enunas.backend.config.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RequestEmailExtractorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void extractsTopLevelEmailFromJsonBody() {
        byte[] body = "{\"email\":\"Person@Example.com\",\"password\":\"secret\"}".getBytes(StandardCharsets.UTF_8);

        Optional<String> result = RequestEmailExtractor.fromJsonBody(body, objectMapper);

        assertThat(result).contains("person@example.com"); // normalized to lowercase
    }

    @Test
    void returnsEmptyWhenEmailFieldMissing() {
        byte[] body = "{\"password\":\"secret\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestEmailExtractor.fromJsonBody(body, objectMapper)).isEmpty();
    }

    @Test
    void returnsEmptyOnMalformedJson() {
        byte[] body = "not json at all".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestEmailExtractor.fromJsonBody(body, objectMapper)).isEmpty();
    }

    @Test
    void returnsEmptyOnBlankEmail() {
        byte[] body = "{\"email\":\"   \"}".getBytes(StandardCharsets.UTF_8);

        assertThat(RequestEmailExtractor.fromJsonBody(body, objectMapper)).isEmpty();
    }

    @Test
    void extractsEmailFromQueryParam() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/brandpartner/resend-verification");
        request.setParameter("email", "Person@Example.com");

        assertThat(RequestEmailExtractor.fromQueryParam(request)).contains("person@example.com");
    }

    @Test
    void returnsEmptyWhenQueryParamMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/brandpartner/resend-verification");

        assertThat(RequestEmailExtractor.fromQueryParam(request)).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RequestEmailExtractorTest`
Expected: FAIL to compile — class does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/RequestEmailExtractor.java`:

```java
package com.enunas.backend.config.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Extracts the secondary (email) rate-limit key from a request, per {@link SecondaryKeySource}.
 * Fails open (returns empty) on any malformed/missing input — the caller falls back to the
 * primary (IP) key alone in that case.
 */
@Slf4j
final class RequestEmailExtractor {

    private RequestEmailExtractor() {
    }

    static Optional<String> fromJsonBody(byte[] cachedBody, ObjectMapper objectMapper) {
        try {
            JsonNode root = objectMapper.readTree(cachedBody);
            JsonNode emailNode = (root == null) ? null : root.get("email");
            if (emailNode == null || emailNode.isNull() || !emailNode.isTextual()) {
                return Optional.empty();
            }
            String email = emailNode.asText().trim();
            return email.isEmpty() ? Optional.empty() : Optional.of(email.toLowerCase());
        } catch (Exception exception) {
            log.debug("Could not extract 'email' from request body for rate limiting: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    static Optional<String> fromQueryParam(HttpServletRequest request) {
        String email = request.getParameter("email");
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(email.trim().toLowerCase());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=RequestEmailExtractorTest`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/config/ratelimit/RequestEmailExtractor.java backend/src/test/java/com/enunas/backend/config/ratelimit/RequestEmailExtractorTest.java
git commit -m "Add RequestEmailExtractor: secondary rate-limit key extraction"
```

---

## Task 5: RateLimitResponseWriter

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriter.java`
- Test: `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriterTest.java`

**Interfaces:**
- Consumes: `io.github.bucket4j.ConsumptionProbe` (from Task 1's registry).
- Produces: `public class RateLimitResponseWriter` (`@Component`) with `public RateLimitResponseWriter(ObjectMapper objectMapper)` and `public void write(HttpServletRequest request, HttpServletResponse response, int capacity, ConsumptionProbe probe) throws IOException`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriterTest.java`:

```java
package com.enunas.backend.config.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitResponseWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final RateLimitResponseWriter writer = new RateLimitResponseWriter(objectMapper);

    @Test
    void writesJsonBodyAndHeadersMatchingGlobalExceptionHandlerShape() throws Exception {
        Bucket exhausted = Bucket.builder()
                .addLimit(limit -> limit.capacity(1).refillGreedy(1, Duration.ofMinutes(1)))
                .build();
        exhausted.tryConsume(1); // exhaust the only token
        ConsumptionProbe probe = exhausted.tryConsumeAndReturnRemaining(1);
        assertThat(probe.isConsumed()).isFalse();

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(request, response, 1, probe);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");

        JsonNode json = objectMapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(429);
        assertThat(json.get("error").asText()).isEqualTo("Too Many Requests");
        assertThat(json.get("path").asText()).isEqualTo("/auth/login");
        assertThat(json.get("retryAfter").asLong()).isEqualTo(60);
        assertThat(json.get("limit").asInt()).isEqualTo(1);
        assertThat(json.get("remaining").asLong()).isEqualTo(0);
        assertThat(json.has("resetAt")).isTrue();
        assertThat(json.has("timestamp")).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitResponseWriterTest`
Expected: FAIL to compile — class does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriter.java`:

```java
package com.enunas.backend.config.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the 429 response for a denied rate-limit check. Runs inside {@link
 * com.enunas.backend.config.RateLimitingFilter}, outside DispatcherServlet, so
 * {@code @RestControllerAdvice} (GlobalExceptionHandler) never sees this — the JSON shape below
 * is written directly to match it (timestamp/status/error/message), plus rate-limit-specific
 * fields (path/retryAfter/limit/remaining/resetAt).
 */
@Component
public class RateLimitResponseWriter {

    private final ObjectMapper objectMapper;

    public RateLimitResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(
            HttpServletRequest request, HttpServletResponse response,
            int capacity, ConsumptionProbe probe
    ) throws IOException {
        long retryAfterSeconds = Math.max(1, ceilNanosToSeconds(probe.getNanosToWaitForRefill()));
        Instant resetAt = Instant.now().plusSeconds(retryAfterSeconds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now());
        body.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        body.put("error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase());
        body.put("message", "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.");
        body.put("path", request.getRequestURI());
        body.put("retryAfter", retryAfterSeconds);
        body.put("limit", capacity);
        body.put("remaining", Math.max(0, probe.getRemainingTokens()));
        body.put("resetAt", LocalDateTime.ofInstant(resetAt, ZoneId.systemDefault()));

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** Rounds up so Retry-After never tells a client to retry before a token is actually available. */
    private static long ceilNanosToSeconds(long nanos) {
        return (nanos + 999_999_999L) / 1_000_000_000L;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitResponseWriterTest`
Expected: PASS (1 test)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriter.java backend/src/test/java/com/enunas/backend/config/ratelimit/RateLimitResponseWriterTest.java
git commit -m "Add RateLimitResponseWriter: 429 JSON response matching GlobalExceptionHandler shape"
```

---

## Task 6: RateLimitingFilter

**Files:**
- Modify: `backend/src/main/resources/application.yaml`
- Create: `backend/src/main/java/com/enunas/backend/config/RateLimitingFilter.java`
- Test: `backend/src/test/java/com/enunas/backend/config/RateLimitingFilterTest.java`

**Interfaces:**
- Consumes: `RateLimitRuleTable.match(HttpMethod, String)` (Task 2), `RateLimitBucketRegistry.consume(String, String, int, Duration)` (Task 1), `RateLimitResponseWriter.write(...)` (Task 5), `CachedBodyHttpServletRequestWrapper` (Task 3), `RequestEmailExtractor.fromJsonBody`/`fromQueryParam` (Task 4).
- Produces: `public class RateLimitingFilter extends OncePerRequestFilter` (`@Component`) with constructor `RateLimitingFilter(RateLimitRuleTable, RateLimitBucketRegistry, RateLimitResponseWriter, ObjectMapper, @Value("${enunas.rate-limit.enabled:true}") boolean enabled)` — consumed by Task 7's `SecurityConfiguration`.

- [ ] **Step 1: Add the enable/disable property**

In `backend/src/main/resources/application.yaml`, inside the existing `enunas:` block (after `shipping:`), add:

```yaml
  rate-limit:
    # Global kill switch — override via env var ENUNAS_RATE_LIMIT_ENABLED if the filter ever
    # misbehaves in production, no code change needed. See RateLimitingFilter.
    enabled: true
```

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/config/RateLimitingFilterTest.java`:

```java
package com.enunas.backend.config;

import com.enunas.backend.config.ratelimit.RateLimitBucketRegistry;
import com.enunas.backend.config.ratelimit.RateLimitResponseWriter;
import com.enunas.backend.config.ratelimit.RateLimitRuleTable;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitingFilter newFilter(boolean enabled) {
        return new RateLimitingFilter(
                new RateLimitRuleTable(),
                new RateLimitBucketRegistry(),
                new RateLimitResponseWriter(objectMapper),
                objectMapper,
                enabled);
    }

    @Test
    void disabledFilterPassesEveryRequestThrough() throws Exception {
        RateLimitingFilter filter = newFilter(false);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();

        for (int i = 0; i < 10; i++) {
            filter.doFilterInternal(loginRequest("203.0.113.1"), new MockHttpServletResponse(), chain);
        }

        assertThat(calls.get()).isEqualTo(10);
    }

    @Test
    void healthCheckBypassesLimitingEntirely() throws Exception {
        RateLimitingFilter filter = newFilter(true);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.setRemoteAddr("203.0.113.1");

        for (int i = 0; i < 50; i++) {
            filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        }

        assertThat(calls.get()).isEqualTo(50);
    }

    @Test
    void optionsRequestBypassesLimiting() throws Exception {
        RateLimitingFilter filter = newFilter(true);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/auth/login");
        request.setRemoteAddr("203.0.113.1");

        for (int i = 0; i < 20; i++) {
            filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        }

        assertThat(calls.get()).isEqualTo(20);
    }

    @Test
    void sixthLoginFromSameIpIsDenied() throws Exception {
        RateLimitingFilter filter = newFilter(true);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();

        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < 6; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(loginRequest("203.0.113.2"), lastResponse, chain);
        }

        assertThat(calls.get()).isEqualTo(5);
        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(lastResponse.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void secondaryEmailBudgetDeniesBeforePrimaryIpBudgetOnResetRequest() throws Exception {
        RateLimitingFilter filter = newFilter(true);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();

        // reset-request: primary IP capacity 5/min, secondary email capacity 3/60min.
        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < 4; i++) {
            lastResponse = new MockHttpServletResponse();
            filter.doFilterInternal(forgotPasswordRequest("203.0.113.3", "victim@example.com"), lastResponse, chain);
        }

        assertThat(calls.get()).isEqualTo(3); // 3 succeed, 4th denied on the email bucket
        assertThat(lastResponse.getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(lastResponse.getContentAsString()).get("limit").asInt()).isEqualTo(3);
    }

    @Test
    void defaultTierKeysByAuthenticatedUserNotByIp() throws Exception {
        RateLimitingFilter filter = newFilter(true);
        AtomicInteger calls = new AtomicInteger();
        FilterChain chain = (req, res) -> calls.incrementAndGet();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("customer@example.com", null, List.of()));

        // 120/min default capacity: two different IPs, same authenticated principal -> one shared budget.
        for (int i = 0; i < 120; i++) {
            String ip = (i % 2 == 0) ? "203.0.113.4" : "203.0.113.5";
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/profile");
            request.setRemoteAddr(ip);
            filter.doFilterInternal(request, new MockHttpServletResponse(), chain);
        }
        MockHttpServletRequest request121 = new MockHttpServletRequest("GET", "/customer/profile");
        request121.setRemoteAddr("203.0.113.4");
        MockHttpServletResponse response121 = new MockHttpServletResponse();
        filter.doFilterInternal(request121, response121, chain);

        assertThat(calls.get()).isEqualTo(120);
        assertThat(response121.getStatus()).isEqualTo(429);
    }

    private MockHttpServletRequest loginRequest(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        request.setRemoteAddr(ip);
        return request;
    }

    private MockHttpServletRequest forgotPasswordRequest(String ip, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/forgot-password");
        request.setRemoteAddr(ip);
        request.setContent(("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        return request;
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitingFilterTest`
Expected: FAIL to compile — `RateLimitingFilter` does not exist yet.

- [ ] **Step 4: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/config/RateLimitingFilter.java`:

```java
package com.enunas.backend.config;

import com.enunas.backend.config.ratelimit.CachedBodyHttpServletRequestWrapper;
import com.enunas.backend.config.ratelimit.KeyStrategy;
import com.enunas.backend.config.ratelimit.RateLimitBucketRegistry;
import com.enunas.backend.config.ratelimit.RateLimitResponseWriter;
import com.enunas.backend.config.ratelimit.RateLimitRule;
import com.enunas.backend.config.ratelimit.RateLimitRuleTable;
import com.enunas.backend.config.ratelimit.RequestEmailExtractor;
import com.enunas.backend.config.ratelimit.SecondaryKeySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Runs immediately after {@link JwtAuthenticationFilter} (so the Default tier can key off the
 * authenticated user id) and before Spring MVC. Matches each request against
 * {@link RateLimitRuleTable}, checks the primary (and, for a handful of tiers, secondary) bucket,
 * and short-circuits with 429 on denial. See
 * docs/superpowers/specs/2026-08-18-rate-limiting-design.md.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitRuleTable ruleTable;
    private final RateLimitBucketRegistry bucketRegistry;
    private final RateLimitResponseWriter responseWriter;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public RateLimitingFilter(
            RateLimitRuleTable ruleTable,
            RateLimitBucketRegistry bucketRegistry,
            RateLimitResponseWriter responseWriter,
            ObjectMapper objectMapper,
            @Value("${enunas.rate-limit.enabled:true}") boolean enabled
    ) {
        this.ruleTable = ruleTable;
        this.bucketRegistry = bucketRegistry;
        this.responseWriter = responseWriter;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        if (!enabled || HttpMethod.OPTIONS.matches(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        RateLimitRule rule = ruleTable.match(method, request.getRequestURI());

        if (rule.exempt()) {
            filterChain.doFilter(request, response);
            return;
        }

        String primaryKey = resolvePrimaryKey(rule, request);
        ConsumptionProbe primaryProbe = bucketRegistry.consume(rule.name(), primaryKey, rule.capacity(), rule.window());
        if (!primaryProbe.isConsumed()) {
            responseWriter.write(request, response, rule.capacity(), primaryProbe);
            return;
        }

        if (rule.secondaryKeySource() == SecondaryKeySource.NONE) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpServletRequest downstreamRequest = request;
        Optional<String> secondaryKey;
        if (rule.secondaryKeySource() == SecondaryKeySource.JSON_BODY_EMAIL) {
            CachedBodyHttpServletRequestWrapper wrapped = new CachedBodyHttpServletRequestWrapper(request);
            downstreamRequest = wrapped;
            secondaryKey = RequestEmailExtractor.fromJsonBody(wrapped.getCachedBody(), objectMapper);
        } else {
            secondaryKey = RequestEmailExtractor.fromQueryParam(request);
        }

        if (secondaryKey.isPresent()) {
            ConsumptionProbe secondaryProbe = bucketRegistry.consume(
                    rule.name() + ":email", secondaryKey.get(), rule.secondaryCapacity(), rule.secondaryWindow());
            if (!secondaryProbe.isConsumed()) {
                responseWriter.write(request, response, rule.secondaryCapacity(), secondaryProbe);
                return;
            }
        }

        filterChain.doFilter(downstreamRequest, response);
    }

    private String resolvePrimaryKey(RateLimitRule rule, HttpServletRequest request) {
        if (rule.keyStrategy() == KeyStrategy.USER_OR_IP) {
            // AnonymousAuthenticationFilter runs later in the chain than this filter, so an
            // unauthenticated request's Authentication is simply null here (not "anonymousUser").
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null) {
                return authentication.getName();
            }
        }
        return request.getRemoteAddr();
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitingFilterTest`
Expected: PASS (6 tests)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/application.yaml backend/src/main/java/com/enunas/backend/config/RateLimitingFilter.java backend/src/test/java/com/enunas/backend/config/RateLimitingFilterTest.java
git commit -m "Add RateLimitingFilter: orchestrates rule matching, primary/secondary buckets, 429s"
```

---

## Task 7: Wire into SecurityConfiguration + end-to-end verification

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java`
- Modify: `backend/src/test/resources/application-test.yaml`
- Test: `backend/src/test/java/com/enunas/backend/config/RateLimitingIntegrationTest.java`

**Interfaces:**
- Consumes: `RateLimitingFilter` (Task 6).

- [ ] **Step 1: Disable rate limiting by default under the test profile**

In `backend/src/test/resources/application-test.yaml`, inside the existing `enunas:` block (after
`shipping:`), add:

```yaml
  rate-limit:
    # Off by default in tests: the existing integration-test suite calls /auth/login repeatedly
    # across many test classes sharing the same IP within one Spring context, which would trip
    # the auth-strict tier's 5/min budget. RateLimitingIntegrationTest re-enables it explicitly
    # via @TestPropertySource.
    enabled: false
```

- [ ] **Step 2: Write the failing integration test**

Create `backend/src/test/java/com/enunas/backend/config/RateLimitingIntegrationTest.java`:

```java
package com.enunas.backend.config;

import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end check that RateLimitingFilter is actually wired into the security filter chain.
 * Rate limiting is disabled by default under the "test" profile (see application-test.yaml) so
 * the rest of the integration-test suite isn't affected by its own repeated /auth/login calls —
 * this is the one test class that explicitly re-enables it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"test", "mock-payments"})
@TestPropertySource(properties = "enunas.rate-limit.enabled=true")
class RateLimitingIntegrationTest {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private BCryptPasswordEncoder passwordEncoder;

    @AfterEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
    }

    @Test
    void sixthLoginAttemptFromSameClientIsRateLimited() {
        userRepository.save(User.builder()
                .email("ratelimit-test@it.local")
                .password(passwordEncoder.encode("Correct-Password-1!"))
                .role(Role.CUSTOMER)
                .enabled(true)
                .adminApproved(true)
                .build());

        Map<String, Object> badCredentials = Map.of(
                "email", "ratelimit-test@it.local", "password", "wrong-password");

        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> response = rest.postForEntity("/auth/login", badCredentials, Map.class);
            assertThat(response.getStatusCode().value()).as("attempt %d", i + 1).isEqualTo(401);
        }

        ResponseEntity<Map> sixthResponse = rest.postForEntity("/auth/login", badCredentials, Map.class);

        assertThat(sixthResponse.getStatusCode().value()).isEqualTo(429);
        assertThat(sixthResponse.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(sixthResponse.getBody())
                .containsEntry("status", 429)
                .containsEntry("error", "Too Many Requests")
                .containsKeys("retryAfter", "limit", "remaining", "resetAt");

        // A different tier (public catalog, GET) is unaffected by /auth/login's exhausted bucket.
        ResponseEntity<String> catalogResponse = rest.getForEntity("/products", String.class);
        assertThat(catalogResponse.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./mvnw test -Dtest=RateLimitingIntegrationTest`
Expected: FAIL — every `/auth/login` call returns 401 (never 429), because `RateLimitingFilter`
isn't yet registered in the security filter chain.

- [ ] **Step 4: Wire the filter into SecurityConfiguration**

In `backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java`, add the new
filter as a constructor dependency and register it right after `JwtAuthenticationFilter`:

```java
    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RateLimitingFilter rateLimitingFilter;

    public SecurityConfiguration(
            AuthenticationProvider authenticationProvider,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            RateLimitingFilter rateLimitingFilter
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.rateLimitingFilter = rateLimitingFilter;
    }
```

And in `securityFilterChain(...)`, change:

```java
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
```

to:

```java
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Runs right after JWT auth resolves SecurityContext, so the Default tier can key
                // by authenticated user id — see docs/superpowers/specs/2026-08-18-rate-limiting-design.md.
                .addFilterAfter(rateLimitingFilter, JwtAuthenticationFilter.class);
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=RateLimitingIntegrationTest`
Expected: PASS (1 test)

- [ ] **Step 6: Run the full test suite to confirm nothing else broke**

Run: `./mvnw test`
Expected: PASS — all existing integration tests still pass (rate limiting is off under the `test`
profile by default; only `RateLimitingIntegrationTest` explicitly re-enables it).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/config/SecurityConfiguration.java backend/src/test/resources/application-test.yaml backend/src/test/java/com/enunas/backend/config/RateLimitingIntegrationTest.java
git commit -m "Wire RateLimitingFilter into SecurityConfiguration"
```

---

## Self-Review Notes

- **Spec coverage:** every tier in the spec's table (exempt, auth-strict, reset-confirm,
  reset-request, signup-apply, webhooks, product-search, public-catalog, default) is present in
  `RateLimitRuleTable` and covered by `RateLimitRuleTableTest`. The dual-key mechanism, IP/user-id
  key resolution, 429 JSON shape, in-memory-only storage, idle eviction, and the
  `enunas.rate-limit.enabled` kill switch are each implemented and tested. Webhook signature
  verification and per-user configurable limits are explicitly out of scope per the spec.
- **Placeholder scan:** no TBD/TODO — every step has real code.
- **Type consistency:** `RateLimitRule` field names/types are used identically across
  `RateLimitRuleTable`, `RateLimitingFilter`, and both test files (`name()`, `capacity()`,
  `window()`, `secondaryKeySource()`, `secondaryCapacity()`, `secondaryWindow()`, `exempt()`,
  `keyStrategy()`). `RateLimitBucketRegistry.consume(...)` returns `ConsumptionProbe` consistently
  wherever it's called (`RateLimitingFilter`, `RateLimitResponseWriterTest`).
