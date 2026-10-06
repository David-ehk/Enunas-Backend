package com.enunas.backend.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Map;

/** Binds {@code app.rate-limit.*}. Which paths belong to which rule is code, see {@link RateLimitRule}. */
@ConfigurationProperties("app.rate-limit")
@Validated
public record RateLimitProperties(boolean enabled, @Positive int maxEntries, Map<RateLimitRule, @Valid Rule> rules) {

    public record Rule(@Positive int limit, @NotNull @DurationMin(millis = 1) Duration window) {}
}
