package com.enunas.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Bean Validation on {@link RateLimitProperties} -- not the filter constructor -- rejects a
 * bad config at startup binding time. Replaces filter-constructor unit tests for value validity;
 * the filter itself keeps only the rule-completeness check (see RateLimitFilterTest).
 */
class RateLimitPropertiesValidationTest {

    @EnableConfigurationProperties(RateLimitProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    /** Positive control: without it, the failure tests below would pass on any unrelated startup error. */
    @Test
    void validConfig_starts() {
        runner.withPropertyValues(
                        "app.rate-limit.max-entries=100",
                        "app.rate-limit.rules.login.limit=1",
                        "app.rate-limit.rules.login.window=1m")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nonPositiveMaxEntries_failsAtStartup() {
        runner.withPropertyValues(
                        "app.rate-limit.max-entries=0",
                        "app.rate-limit.rules.login.limit=1",
                        "app.rate-limit.rules.login.window=1m")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxEntries"));
    }

    @Test
    void nonPositiveLimit_failsAtStartup() {
        runner.withPropertyValues(
                        "app.rate-limit.max-entries=100",
                        "app.rate-limit.rules.login.limit=0",
                        "app.rate-limit.rules.login.window=1m")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("limit"));
    }

    @Test
    void missingWindow_failsAtStartup() {
        runner.withPropertyValues(
                        "app.rate-limit.max-entries=100",
                        "app.rate-limit.rules.login.limit=1")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("window"));
    }
}
