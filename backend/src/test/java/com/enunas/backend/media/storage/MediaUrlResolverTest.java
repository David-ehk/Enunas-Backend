package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaUrlResolverTest {

    private static final String PRODUCT_BUCKET = "enunas-clothing-images";
    private static final String BRAND_BUCKET = "enunas-brand-previews";

    private MediaStorageProperties properties(String cdnBaseUrl, String endpoint) {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.getBuckets().setProduct(PRODUCT_BUCKET);
        properties.getBuckets().setBrand(BRAND_BUCKET);
        properties.setRegion("eu-central-1");
        properties.setCdnBaseUrl(cdnBaseUrl);
        properties.setEndpoint(endpoint);
        return properties;
    }

    private MediaUrlResolver resolver(String cdnBaseUrl) {
        return new MediaUrlResolver(properties(cdnBaseUrl, null));
    }

    // ===== CDN configured — one host in front of both buckets =====

    @Test
    void resolve_prependsCdnBase() {
        assertThat(resolver("https://cdn.enunas.com").resolve("products/1/images/abc.jpg"))
                .isEqualTo("https://cdn.enunas.com/products/1/images/abc.jpg");
    }

    @Test
    void resolve_nullKey_returnsNull() {
        assertThat(resolver("https://cdn.enunas.com").resolve(null)).isNull();
    }

    @Test
    void resolve_blankKey_returnsNull() {
        assertThat(resolver("https://cdn.enunas.com").resolve("")).isNull();
    }

    @Test
    void resolve_cdnBaseUrlWithTrailingSlash_noDoubleSlash() {
        assertThat(resolver("https://cdn.enunas.com/").resolve("products/1/images/abc.jpg"))
                .isEqualTo("https://cdn.enunas.com/products/1/images/abc.jpg");
    }

    // ===== No CDN (the current production setup) — direct, per-bucket S3 URLs =====

    @Test
    void resolve_noCdn_productKey_pointsAtTheProductBucketHost() {
        assertThat(resolver(null).resolve("products/1/images/abc.jpg"))
                .isEqualTo("https://" + PRODUCT_BUCKET + ".s3.eu-central-1.amazonaws.com/products/1/images/abc.jpg");
    }

    /** The whole point of deriving the bucket from the key: the two buckets are different hosts. */
    @Test
    void resolve_noCdn_brandKey_pointsAtTheBrandBucketHost() {
        assertThat(resolver("").resolve("brands/7/logo/abc.png"))
                .isEqualTo("https://" + BRAND_BUCKET + ".s3.eu-central-1.amazonaws.com/brands/7/logo/abc.png");
    }

    @Test
    void resolve_noCdn_unknownPrefix_throwsRatherThanEmittingABrokenHost() {
        assertThatThrownBy(() -> resolver(null).resolve("elsewhere/1/abc.png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no known media scope");
    }

    /** LocalStack/S3Mock is path-style, matching S3Config's pathStyleAccessEnabled. */
    @Test
    void resolve_noCdn_withEndpointOverride_usesPathStyle() {
        MediaUrlResolver resolver = new MediaUrlResolver(properties(null, "http://localhost:9090/"));

        assertThat(resolver.resolve("brands/7/logo/abc.png"))
                .isEqualTo("http://localhost:9090/" + BRAND_BUCKET + "/brands/7/logo/abc.png");
    }
}
