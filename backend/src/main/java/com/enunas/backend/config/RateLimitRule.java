package com.enunas.backend.config;

import org.springframework.http.HttpMethod;
import org.springframework.util.AntPathMatcher;

import java.util.List;

/**
 * One rate-limit rule per constant, in match-priority order -- the first constant whose method and
 * pattern match wins. GLOBAL is last and matches any method against {@code "/**"}, so it's always
 * the fallback for a request no earlier rule claimed.
 */
public enum RateLimitRule {
    LOGIN(HttpMethod.POST, false, "/auth/login", "/auth/google"),
    SIGNUP(HttpMethod.POST, false, "/auth/signup", "/brandpartner/apply"),
    PASSWORD_EMAIL(HttpMethod.POST, false, "/auth/forgot-password", "/brandpartner/resend-verification"),
    PASSWORD_CHANGE(HttpMethod.POST, false, "/auth/reset-password", "/auth/change-password",
            "/auth/set-password", "/brandpartner/verify"),
    CHECKOUT(HttpMethod.POST, true, "/orders", "/orders/preview"),
    RETURNS(HttpMethod.POST, true, "/orders/*/return"),
    MEDIA(HttpMethod.POST, true, "/products/*/media/**", "/brandpartner/media/upload-url"),
    WEBHOOK(HttpMethod.POST, false, "/webhooks/mollie"),
    GLOBAL(null, true, "/**");

    private final HttpMethod method;
    private final boolean byUser;
    private final List<String> patterns;

    RateLimitRule(HttpMethod method, boolean byUser, String... patterns) {
        this.method = method;
        this.byUser = byUser;
        this.patterns = List.of(patterns);
    }

    boolean byUser() {
        return byUser;
    }

    /** Kebab-case config key this rule binds to, e.g. {@code PASSWORD_EMAIL} -> {@code "password-email"}. */
    String configKey() {
        return name().toLowerCase().replace('_', '-');
    }

    boolean matches(String requestMethod, String path, AntPathMatcher matcher) {
        return (method == null || method.matches(requestMethod))
                && patterns.stream().anyMatch(p -> matcher.match(p, path));
    }
}
