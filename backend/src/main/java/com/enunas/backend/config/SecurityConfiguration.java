package com.enunas.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfiguration {

    private final AuthenticationProvider authenticationProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfiguration(
            AuthenticationProvider authenticationProvider,
            JwtAuthenticationFilter jwtAuthenticationFilter
    ) {
        this.authenticationProvider = authenticationProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(authorize -> authorize

                        // Public — no token required
                        .requestMatchers("/auth/**", "/public/**", "/error").permitAll()
                        // Public brand-partner application + email verification
                        .requestMatchers(HttpMethod.POST,
                                "/brandpartner/apply",
                                "/brandpartner/verify",
                                "/brandpartner/resend-verification").permitAll()

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

                        // Product and listing reads — any authenticated user
                        .requestMatchers("/products/**").authenticated()
                        .requestMatchers("/listings/**").authenticated()

                        // Customer-only routes
                        .requestMatchers("/customer/**", "/profile/**", "/orders/**", "/checkout/**").hasRole("CUSTOMER")

                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        configuration.setAllowedOrigins(List.of(
                "http://localhost:3000",
                "https://deine-domain.com"
        ));

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
