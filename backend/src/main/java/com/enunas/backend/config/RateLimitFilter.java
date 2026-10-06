package com.enunas.backend.config;

import com.enunas.backend.user.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window, in-memory rate limiting. Exactly one rule applies per request: the first matching
 * {@link RateLimitRule}, with GLOBAL last so it always matches as a fallback. Runs after
 * {@link JwtAuthenticationFilter} so user-keyed rules can see the caller. Single-instance only:
 * counters are per JVM (see the spec's Future section for Redis).
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private record Window(long resetAt, int count) {}

    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final RateLimitProperties props;
    private final Clock clock;
    private final HandlerExceptionResolver resolver;
    private final Map<RateLimitRule, RateLimitProperties.Rule> rules;
    private volatile long lastSweep;

    public RateLimitFilter(RateLimitProperties props, Clock clock, HandlerExceptionResolver resolver) {
        this.props = props;
        this.clock = clock;
        this.resolver = resolver;
        // Fail at startup, not on the first request that needs a missing rule; build the completed
        // map once so every request lookup is a plain get. Value validity (positive limit/max-entries,
        // sane window/ttl) is enforced by Bean Validation on RateLimitProperties itself.
        Map<RateLimitRule, RateLimitProperties.Rule> validated = new EnumMap<>(RateLimitRule.class);
        for (RateLimitRule rule : RateLimitRule.values()) {
            RateLimitProperties.Rule configured = props.rules() == null ? null : props.rules().get(rule);
            if (configured == null) {
                throw new IllegalStateException("app.rate-limit.rules." + rule.configKey() + " is not configured");
            }
            validated.put(rule, configured);
        }
        this.rules = Map.copyOf(validated);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        return !props.enabled() || "/actuator/health".equals(PATH_HELPER.getPathWithinApplication(request));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // Match on the decoded in-application path, not the raw URI: otherwise a percent-encoded
        // path (e.g. "/auth/%6Cogin") slips past its intended rule into the far looser "global" one.
        String appPath = PATH_HELPER.getPathWithinApplication(request);
        RateLimitRule rule = Arrays.stream(RateLimitRule.values())
                .filter(r -> r.matches(request.getMethod(), appPath, matcher))
                .findFirst().orElseThrow();
        RateLimitProperties.Rule limit = rules.get(rule);
        long now = clock.millis();
        String subjectKey = subject(request, rule.byUser());
        String key = rule.configKey() + ":" + subjectKey;

        sweepExpired(now);
        Window w = windows.compute(key, (k, old) -> {
            if (old == null) {
                if (windows.size() >= props.maxEntries()) {
                    // At capacity and this subject has no window yet -- fail open rather than
                    // closed. Closed would let an attacker lock out every new visitor just by
                    // filling the map with throwaway subjects.
                    return null;
                }
                return new Window(now + limit.window().toMillis(), 1);
            }
            return now >= old.resetAt() ? new Window(now + limit.window().toMillis(), 1)
                    : new Window(old.resetAt(), old.count() + 1);
        });

        if (w == null || w.count() <= limit.limit()) {
            chain.doFilter(request, response);
            return;
        }
        log.debug("Rate limit exceeded: rule={} subject={}", rule.configKey(), subjectKey);
        // Set before delegating: once the resolver writes the body the response is committed and
        // headers are frozen (same rule SecurityConfiguration's entry point follows).
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (w.resetAt() - now + 999) / 1000)));
        resolver.resolveException(request, response, null,
                new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests, please try again later."));
    }

    private static String subject(HttpServletRequest request, boolean byUser) {
        if (byUser) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User user) {
                return "u:" + user.getId();
            }
        }
        return "ip:" + ipKey(request.getRemoteAddr());
    }

    /** IPv6 clients are keyed by their /64 prefix so rotating within it can't dodge the budget. */
    private static String ipKey(String remoteAddr) {
        if (!remoteAddr.contains(":")) {
            return remoteAddr;
        }
        try {
            byte[] bytes = InetAddress.getByName(remoteAddr).getAddress(); // literal, no DNS lookup
            if (bytes.length == 16) {
                return "ip6:" + HexFormat.of().formatHex(bytes, 0, 8);
            }
        } catch (UnknownHostException ignored) {
            // Falls through to the raw address below.
        }
        return remoteAddr;
    }

    /**
     * Sweep expired entries at most once per second, on every request that could insert a new one
     * -- not only once the map is full, or a low-traffic instance would keep stale windows on the
     * heap indefinitely.
     */
    private void sweepExpired(long now) {
        if (now - lastSweep >= 1000) {
            lastSweep = now;
            windows.values().removeIf(w -> now >= w.resetAt());
        }
    }
}
