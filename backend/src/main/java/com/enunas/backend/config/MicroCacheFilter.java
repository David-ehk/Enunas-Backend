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
import org.springframework.web.util.UrlPathHelper;

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
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    /** Never cache a single huge response body (e.g. ?size=2000) wholesale. */
    private static final int MAX_BODY_BYTES = 256 * 1024;

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
                || PATHS.stream().noneMatch(p -> matcher.match(p, PATH_HELPER.getPathWithinApplication(request)));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // Decoded in-application path, not the raw URI: a percent-encoded spelling (e.g.
        // "/products/%31") must share the same cache entry as its decoded form.
        String appPath = PATH_HELPER.getPathWithinApplication(request);
        String key = request.getQueryString() == null ? appPath : appPath + "?" + request.getQueryString();
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
            if (wrapper.getStatus() == HttpServletResponse.SC_OK) {
                byte[] body = wrapper.getContentAsByteArray();
                if (body.length <= MAX_BODY_BYTES && hasRoom(now)) {
                    cache.put(key, new Entry(body, wrapper.getContentType(), now + props.ttl().toMillis()));
                }
            }
        } finally {
            wrapper.copyBodyToResponse();
        }
    }

    /**
     * Sweep expired entries at most once per second, on every request that could insert a new one
     * -- not only once the map is full, or a low-traffic instance would keep stale entries on the
     * heap indefinitely. If still full afterward, don't insert.
     */
    private boolean hasRoom(long now) {
        if (now - lastSweep >= 1000) {
            lastSweep = now;
            cache.values().removeIf(e -> e.expiresAt() <= now);
        }
        return cache.size() < props.maxEntries();
    }
}
