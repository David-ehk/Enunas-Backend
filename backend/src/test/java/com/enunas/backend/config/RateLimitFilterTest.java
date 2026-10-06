package com.enunas.backend.config;

import com.enunas.backend.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitFilterTest {

    /** Stands in for GlobalExceptionHandler in these filter-only unit tests: just sets the status. */
    private static final HandlerExceptionResolver STUB_RESOLVER = (request, response, handler, ex) -> {
        response.setStatus(429);
        return new ModelAndView();
    };

    private final MutableClock clock = new MutableClock();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Everything 100/min except login=2, checkout=2, media=2, password-change=2, global=3. */
    private static RateLimitProperties props(boolean enabled) {
        return props(enabled, 100_000);
    }

    private static RateLimitProperties props(boolean enabled, int maxEntries) {
        Map<RateLimitRule, RateLimitProperties.Rule> rules = new EnumMap<>(RateLimitRule.class);
        for (RateLimitRule r : RateLimitRule.values()) {
            rules.put(r, new RateLimitProperties.Rule(100, Duration.ofMinutes(1)));
        }
        rules.put(RateLimitRule.LOGIN, new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put(RateLimitRule.CHECKOUT, new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put(RateLimitRule.MEDIA, new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put(RateLimitRule.PASSWORD_CHANGE, new RateLimitProperties.Rule(2, Duration.ofMinutes(1)));
        rules.put(RateLimitRule.GLOBAL, new RateLimitProperties.Rule(3, Duration.ofMinutes(1)));
        return new RateLimitProperties(enabled, maxEntries, rules);
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
    void overLimit_returns429WithRetryAfter() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        MockHttpServletResponse third = send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("60");
    }

    /**
     * The 429 body is GlobalExceptionHandler's job, not this filter's -- it must delegate to the
     * resolver instead of hand-writing JSON, so the envelope stays defined in exactly one place.
     */
    @Test
    void overLimit_routesThroughHandlerExceptionResolver() throws Exception {
        AtomicReference<Exception> captured = new AtomicReference<>();
        HandlerExceptionResolver capturing = (request, response, handler, ex) -> {
            captured.set(ex);
            response.setStatus(429);
            return new ModelAndView();
        };
        RateLimitFilter f = new RateLimitFilter(props(true), clock, capturing);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");

        MockHttpServletResponse third = send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isEqualTo("60");
        assertThat(captured.get()).isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
            assertThat(ex.getStatusCode().value()).isEqualTo(429);
            assertThat(ex.getReason()).isEqualTo("Too many requests, please try again later.");
        });
    }

    @Test
    void retryAfter_roundsUpRemainingSeconds() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");
        clock.advance(Duration.ofMillis(59_500));

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getHeader("Retry-After")).isEqualTo("1");
    }

    @Test
    void windowRollover_allowsAgain() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");
        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(429);

        clock.advance(Duration.ofMinutes(1));

        assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void differentIps_haveSeparateBudgets() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(send(f, "POST", "/auth/login", "2.2.2.2").getStatus()).isEqualTo(200);
    }

    @Test
    void userKeyedRule_separatesUsersOnSameIp() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        loginAs(1);
        send(f, "POST", "/orders", "1.1.1.1");
        send(f, "POST", "/orders/preview", "1.1.1.1");
        assertThat(send(f, "POST", "/orders", "1.1.1.1").getStatus()).isEqualTo(429);

        loginAs(2);
        assertThat(send(f, "POST", "/orders", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void unmatchedRequest_fallsIntoGlobalCap() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "GET", "/products", "1.1.1.1");
        send(f, "GET", "/listings", "1.1.1.1");
        send(f, "GET", "/products/1", "1.1.1.1");

        assertThat(send(f, "GET", "/brands/1/public-profile", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    @Test
    void namedRule_doesNotConsumeGlobalBudget() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "POST", "/auth/login", "1.1.1.1");

        assertThat(send(f, "GET", "/products", "1.1.1.1").getStatus()).isEqualTo(200);
    }

    @Test
    void healthCheck_isNeverLimited() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        for (int i = 0; i < 10; i++) {
            assertThat(send(f, "GET", "/actuator/health", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void disabled_passesEverythingThrough() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(false), clock, STUB_RESOLVER);
        for (int i = 0; i < 10; i++) {
            assertThat(send(f, "POST", "/auth/login", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void missingRuleConfig_failsAtConstruction() {
        Map<RateLimitRule, RateLimitProperties.Rule> rules = new EnumMap<>(props(true).rules());
        rules.remove(RateLimitRule.WEBHOOK);

        assertThatThrownBy(() -> new RateLimitFilter(new RateLimitProperties(true, 1, rules), clock, STUB_RESOLVER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.rate-limit.rules.webhook");
    }

    @Test
    void percentEncodedPath_isMatchedAsDecodedLoginRule() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);

        assertThat(send(f, "POST", "/auth/%6Cogin", "3.3.3.3").getStatus()).isEqualTo(200);
        assertThat(send(f, "POST", "/auth/%6Cogin", "3.3.3.3").getStatus()).isEqualTo(200);

        // 3rd request 429s only if this hit login's limit of 2, not global's limit of 3 -- proof
        // the raw (still-encoded) URI isn't what's being matched against the route patterns.
        assertThat(send(f, "POST", "/auth/%6Cogin", "3.3.3.3").getStatus()).isEqualTo(429);
    }

    @Test
    void percentEncodedPath_healthExemptionUsesDecodedPathToo() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);

        for (int i = 0; i < 10; i++) {
            assertThat(send(f, "GET", "/actuator/%68ealth", "1.1.1.1").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void mapAtCapacity_failsOpenThenTracksAgainAfterSweep() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true, 2), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "1.1.1.1");
        send(f, "GET", "/products", "2.2.2.2");

        // Map is full (2/2 maxEntries): a brand-new subject passes through untracked instead of
        // being rejected or evicting one of the two live windows.
        assertThat(send(f, "POST", "/auth/login", "3.3.3.3").getStatus()).isEqualTo(200);
        assertThat(send(f, "POST", "/auth/login", "3.3.3.3").getStatus()).isEqualTo(200);
        assertThat(send(f, "POST", "/auth/login", "3.3.3.3").getStatus()).isEqualTo(200);

        // Once the original windows expire, the sweep frees space and new subjects are tracked again.
        clock.advance(Duration.ofMinutes(1));
        send(f, "POST", "/auth/login", "4.4.4.4");
        send(f, "POST", "/auth/login", "4.4.4.4");
        assertThat(send(f, "POST", "/auth/login", "4.4.4.4").getStatus()).isEqualTo(429);
    }

    @Test
    void presignUploadRoute_sharesMediaBudgetWithProductMediaRoute() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        loginAs(1);
        send(f, "POST", "/brandpartner/media/upload-url", "1.1.1.1");
        send(f, "POST", "/products/1/media/photo", "1.1.1.1");

        assertThat(send(f, "POST", "/brandpartner/media/upload-url", "1.1.1.1").getStatus()).isEqualTo(429);
    }

    // F1: the public brand-partner endpoints have no attempt counter of their own (the verify code
    // is a brute-forceable 6-digit guess), so they must fall under an existing narrow rule instead
    // of the 300/min global cap.
    @Test
    void brandPartnerVerify_isCountedUnderPasswordChangeRule() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/brandpartner/verify", "5.5.5.5");
        send(f, "POST", "/brandpartner/verify", "5.5.5.5");

        // password-change's limit is 2 here; global's is 3 -- 429 on the 3rd call proves this
        // path was counted against password-change, not global.
        assertThat(send(f, "POST", "/brandpartner/verify", "5.5.5.5").getStatus()).isEqualTo(429);
    }

    // F3: an IPv6 client can rotate through addresses in its own /64 to dodge a per-IP limit, so
    // IPv6 subjects are keyed by /64 prefix instead of the full address (IPv4 is unaffected).
    @Test
    void ipv6_sameSlash64_sharesBudget() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "2001:db8::1");
        send(f, "POST", "/auth/login", "2001:db8::2");

        assertThat(send(f, "POST", "/auth/login", "2001:db8::3").getStatus()).isEqualTo(429);
    }

    @Test
    void ipv6_differentSlash64_hasSeparateBudget() throws Exception {
        RateLimitFilter f = new RateLimitFilter(props(true), clock, STUB_RESOLVER);
        send(f, "POST", "/auth/login", "2001:db8::1");
        send(f, "POST", "/auth/login", "2001:db8::2");

        assertThat(send(f, "POST", "/auth/login", "2001:db8:1::1").getStatus()).isEqualTo(200);
    }
}
