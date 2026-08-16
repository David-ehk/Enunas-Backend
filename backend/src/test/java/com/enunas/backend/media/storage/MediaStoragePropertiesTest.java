package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStoragePropertiesTest {

    private MediaStorageProperties properties() {
        MediaStorageProperties p = new MediaStorageProperties();
        p.setBucket("enunas-media");
        p.setRegion("eu-central-1");
        p.setPresignTtl(Duration.ofMinutes(10));
        return p;
    }

    @Test
    void bothBlank_failsFast() {
        MediaStorageProperties p = properties();
        p.setCdnBaseUrl("");
        p.setEndpoint("");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cdn-base-url");
    }

    @Test
    void endpointSet_cdnBlank_isFine() {
        // LocalStack/S3Mock dev/test: endpoint marks a non-production target, no CDN needed.
        MediaStorageProperties p = properties();
        p.setEndpoint("http://localhost:4566");
        p.setCdnBaseUrl("");

        assertThatCode(p::validate).doesNotThrowAnyException();
    }

    @Test
    void cdnSet_endpointBlank_isFine() {
        // Real AWS with a CDN configured — the production-shaped case.
        MediaStorageProperties p = properties();
        p.setEndpoint("");
        p.setCdnBaseUrl("https://cdn.enunas.com");

        assertThatCode(p::validate).doesNotThrowAnyException();
    }
}
