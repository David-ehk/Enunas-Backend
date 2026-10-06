package com.enunas.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Bean Validation on {@link MicroCacheProperties} -- not the filter constructor -- rejects a
 * bad config at startup binding time. Replaces the filter-constructor unit tests for value
 * validity (see MicroCacheFilterTest).
 */
class MicroCachePropertiesValidationTest {

    @EnableConfigurationProperties(MicroCacheProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    /** Positive control: without it, the failure tests below would pass on any unrelated startup error. */
    @Test
    void validConfig_starts() {
        runner.withPropertyValues("app.micro-cache.ttl=1s", "app.micro-cache.max-entries=100")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void nonPositiveMaxEntries_failsAtStartup() {
        runner.withPropertyValues("app.micro-cache.ttl=1s", "app.micro-cache.max-entries=0")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("maxEntries"));
    }

    @Test
    void missingTtl_failsAtStartup() {
        runner.withPropertyValues("app.micro-cache.max-entries=100")
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("ttl"));
    }
}
