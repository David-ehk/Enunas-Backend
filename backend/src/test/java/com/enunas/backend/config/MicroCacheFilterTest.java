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

    // F2: an expired entry only used to be swept once the map was full, so a low-traffic instance
    // that never fills max-entries could keep stale bodies on the heap forever. Sweeping on every
    // insert attempt (still throttled to once/second) fixes that; observable here with a
    // maxEntries(1) cache where a new key must reuse the freed slot after TTL + 1s.
    @Test
    void fullCache_expiredEntrySwept_allowsNewKeyToBeCached() throws Exception {
        MicroCacheFilter f = filter(true, 1);
        get(f, "/products/1", null, null);

        clock.advance(Duration.ofMillis(1001));

        get(f, "/products/2", null, null);
        assertThat(get(f, "/products/2", null, null).getContentAsString()).isEqualTo("{\"n\":2}");
    }

    // F2: a single huge response body (e.g. ?size=2000) must not be cached wholesale.
    @Test
    void oversizedBody_isNotCached() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        byte[] big = new byte[256 * 1024 + 1];
        FilterChain bigController = (req, res) -> {
            calls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(200);
            res.setContentType("application/octet-stream");
            res.getOutputStream().write(big);
        };
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/products/1");
        f.doFilter(req1, new MockHttpServletResponse(), bigController);
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/products/1");
        f.doFilter(req2, new MockHttpServletResponse(), bigController);

        assertThat(calls).hasValue(2);
    }

    // F8: the cache key must be built from the decoded in-application path, same as RateLimitFilter,
    // so a percent-encoded spelling doesn't fragment the cache (or, worse, evade a path-based rule).
    @Test
    void percentEncodedPath_sharesCacheEntryWithDecodedPath() throws Exception {
        MicroCacheFilter f = filter(true, 100);
        get(f, "/products/%31", null, null);

        assertThat(get(f, "/products/1", null, null).getContentAsString()).isEqualTo("{\"n\":1}");
        assertThat(calls).hasValue(1);
    }
}
