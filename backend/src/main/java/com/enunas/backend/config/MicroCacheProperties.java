package com.enunas.backend.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Binds {@code app.micro-cache.*}. */
@ConfigurationProperties("app.micro-cache")
@Validated
public record MicroCacheProperties(boolean enabled, @NotNull @DurationMin(millis = 1) Duration ttl,
                                    @Positive int maxEntries) {}
