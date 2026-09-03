package com.enunas.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final HandlerExceptionResolver handlerExceptionResolver;

    @Value("${app.cors.allowed-origins}")
    private String allowedOriginsProperty;

    public SecurityConfiguration(
            AuthenticationProvider authenticationProvider,
            JwtAuthenticationFilter jwtAuthenticationFilter,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authorize -> authorize

                        // Account-mutating auth routes derive their target account solely from the
                        // injected Authentication — they must never be reachable anonymously.
                        // MUST stay above the /auth/** permitAll below (first match wins).
                        .requestMatchers(HttpMethod.POST, "/auth/set-password", "/auth/change-password").authenticated()

                        // Public — no token required (login, signup, google, forgot/reset-password)
                        .requestMatchers("/auth/**", "/public/**", "/error").permitAll()
                        // Payment webhooks — called server-to-server with no JWT
                        .requestMatchers(HttpMethod.POST, "/webhooks/mollie").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/mock/payment", "/webhooks/mock/refund").permitAll()
                        // Public brand-partner application + email verification
                        .requestMatchers(HttpMethod.POST,
                                "/brandpartner/apply",
                                "/brandpartner/verify",
                                "/brandpartner/resend-verification").permitAll()

                        // Public storefront brand profile — distinct prefix from /brandpartner/**
                        // (which is locked to BRAND_PARTNER/ADMIN below) so this doesn't inherit
                        // that restriction.
                        .requestMatchers(HttpMethod.GET, "/brands/**").permitAll()

                        // Health check — used by load balancers / uptime monitors, no auth
                        .requestMatchers("/actuator/health").permitAll()

                        // Admin only
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // BrandPartner profile and order view (all other /brandpartner/** routes)
                        .requestMatchers("/brandpartner/**").hasAnyRole("BRAND_PARTNER", "ADMIN")
                        .requestMatchers("/brand/**").hasRole("BRAND_PARTNER")

                        // Product write operations — BrandPartner only (ownership enforced in service)
                        .requestMatchers(HttpMethod.POST,   "/products/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.PUT,    "/products/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.PATCH,  "/products/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.DELETE, "/products/**").hasRole("BRAND_PARTNER")

                        // Listing write operations — BrandPartner only
                        .requestMatchers(HttpMethod.POST,   "/listings/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.PUT,    "/listings/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.PATCH,  "/listings/**").hasRole("BRAND_PARTNER")
                        .requestMatchers(HttpMethod.DELETE, "/listings/**").hasRole("BRAND_PARTNER")

                        // Public storefront reads — catalog is browsable without a login.
                        // (Brand-only GET /products/my keeps its own @PreAuthorize.)
                        .requestMatchers(HttpMethod.GET, "/products/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/listings/**").permitAll()

                        // Customer-only routes
                        .requestMatchers("/customer/**", "/profile/**", "/orders/**", "/checkout/**", "/wardrobe/**").hasRole("CUSTOMER")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                // Denials raised by the authorizeHttpRequests rules above happen in the filter
                // chain, before the DispatcherServlet exists, so @RestControllerAdvice never sees
                // them: Spring Security's defaults answered them with an empty body (and 403 even
                // for anonymous callers, which reads as "you may not" when the truth is "who are
                // you?"). Both are routed back through the same HandlerExceptionResolver the JWT
                // filter already uses, so GlobalExceptionHandler stays the single definition of the
                // error envelope instead of a second copy drifting in this file.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint())
                        .accessDeniedHandler(accessDeniedHandler())
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /** No credentials, or credentials the chain could not authenticate -> 401. */
    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            // RFC 9110 §11.6.1 makes WWW-Authenticate mandatory on a 401. Set before delegating:
            // once the resolver writes the body the response is committed and headers are frozen.
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            resolveOrFallBack(request, response, authException, HttpServletResponse.SC_UNAUTHORIZED);
        };
    }

    /** Authenticated, but the URL rule denies this principal -> 403. */
    private AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) ->
                resolveOrFallBack(request, response, accessDeniedException, HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * resolveException returns null when no @ExceptionHandler matched, and in that case nothing has
     * been written — the caller would receive a blank 200. The status-only fallback keeps a missing
     * handler from turning a denial into an apparent success.
     */
    private void resolveOrFallBack(HttpServletRequest request, HttpServletResponse response,
                                   Exception ex, int fallbackStatus) throws IOException {
        if (handlerExceptionResolver.resolveException(request, response, null, ex) == null) {
            log.warn("No @ExceptionHandler for {}; falling back to bare {}",
                    ex.getClass().getSimpleName(), fallbackStatus);
            response.sendError(fallbackStatus);
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Comma-separated, exact origins (scheme + host, no path/trailing slash) — see
        // app.cors.allowed-origins / CORS_ALLOWED_ORIGINS. apex and www count as different
        // origins to the browser, so both must be listed explicitly if both are live.
        configuration.setAllowedOrigins(Arrays.stream(allowedOriginsProperty.split(","))
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList());

        configuration.setAllowedMethods(List.of(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        configuration.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "Accept", "X-Requested-With"
        ));

        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
