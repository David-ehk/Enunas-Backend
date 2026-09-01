package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaStoragePropertiesTest {

    private MediaStorageProperties properties() {
        MediaStorageProperties p = new MediaStorageProperties();
        p.getBuckets().setProduct("enunas-clothing-images");
        p.getBuckets().setBrand("enunas-brand-previews");
        p.setRegion("eu-central-1");
        p.setPresignTtl(Duration.ofMinutes(10));
        return p;
    }

    @Test
    void bothBucketsSet_isFine() {
        assertThatCode(properties()::validate).doesNotThrowAnyException();
    }

    /**
     * The whole reason the {@code enunas-media} default was removed: a missing variable has to stop
     * startup, not surface as a NoSuchKey/NoSuchBucket on every upload once the app is already live.
     */
    @Test
    void productBucketMissing_failsFast_namingTheEnvironmentVariable() {
        MediaStorageProperties p = properties();
        p.getBuckets().setProduct("  ");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_BUCKET_PRODUCTS");
    }

    @Test
    void brandBucketMissing_failsFast_namingTheEnvironmentVariable() {
        MediaStorageProperties p = properties();
        p.getBuckets().setBrand(null);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_BUCKET_BRAND_PREVIEWS");
    }

    /**
     * No longer a startup failure: there is no CloudFront in front of the buckets, so a blank
     * cdn-base-url is the normal production setup and MediaUrlResolver emits direct S3 URLs.
     */
    @Test
    void noCdnAndNoEndpoint_isFine() {
        MediaStorageProperties p = properties();
        p.setCdnBaseUrl("");
        p.setEndpoint("");

        assertThatCode(p::validate).doesNotThrowAnyException();
    }

    /** Region is part of the public S3 hostname when no CDN is configured — a blank one is fatal. */
    @Test
    void regionMissing_failsFast() {
        MediaStorageProperties p = properties();
        p.setRegion("");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AWS_REGION");
    }

    /**
     * A scheme-less value is pasted into the URL as-is and turns into a relative path — the browser
     * resolves it against the API host and 404s, which reads as missing media rather than as a
     * broken setting. Cheaper to catch at boot than in someone's browser console.
     */
    @Test
    void cdnBaseUrlWithoutScheme_failsFast() {
        MediaStorageProperties p = properties();
        p.setCdnBaseUrl("cdn.enunas.com");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEDIA_CDN_BASE_URL");
    }

    @Test
    void endpointWithoutScheme_failsFast() {
        MediaStorageProperties p = properties();
        p.setEndpoint("localhost:4566");

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("S3_ENDPOINT");
    }

    @Test
    void bucketFor_mapsEachScopeToItsOwnBucket() {
        MediaStorageProperties p = properties();

        assertThat(p.bucketFor(MediaPurpose.Scope.PRODUCT)).isEqualTo("enunas-clothing-images");
        assertThat(p.bucketFor(MediaPurpose.Scope.BRAND)).isEqualTo("enunas-brand-previews");
        assertThat(p.bucketForKey("products/1/images/a.jpg")).isEqualTo("enunas-clothing-images");
        assertThat(p.bucketForKey("brands/1/logo/a.png")).isEqualTo("enunas-brand-previews");
    }
}
