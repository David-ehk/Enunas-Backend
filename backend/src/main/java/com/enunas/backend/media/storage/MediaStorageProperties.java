package com.enunas.backend.media.storage;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds {@code enunas.media.*}. Credentials never appear here — see {@link S3Config} for
 * {@code DefaultCredentialsProvider}.
 */
@Component
@ConfigurationProperties("enunas.media")
@Getter
@Setter
public class MediaStorageProperties {

    /**
     * One bucket per {@link MediaPurpose.Scope}. Two separate buckets, not one with prefixes:
     * product media and brand previews are provisioned independently in AWS. Which bucket a given
     * key belongs to is always derivable from its prefix — see {@link MediaPurpose.Scope#fromKey}.
     */
    private Buckets buckets = new Buckets();
    private String region;
    /**
     * Optional CDN host in front of the buckets. Blank is the normal case today — there is no
     * CloudFront distribution, so {@link MediaUrlResolver} falls back to the direct S3 URL. Set
     * this once a CDN exists and every stored key resolves through it instead, with no data change.
     */
    private String cdnBaseUrl;
    /** Empty = real AWS. Set (e.g. LocalStack/S3Mock) = endpoint override + path-style access. */
    private String endpoint;
    private Duration presignTtl;

    @Getter
    @Setter
    public static class Buckets {
        private String product;
        private String brand;
    }

    public String bucketFor(MediaPurpose.Scope scope) {
        return switch (scope) {
            case PRODUCT -> buckets.getProduct();
            case BRAND -> buckets.getBrand();
        };
    }

    /** The bucket a stored key lives in — the only thing {@code delete}/{@code resolve} ever get. */
    public String bucketForKey(String key) {
        return bucketFor(MediaPurpose.Scope.fromKey(key));
    }

    /**
     * Fail-fast: a missing bucket name is unrecoverable and must stop startup rather than surface
     * as a {@code NoSuchBucket} on the first upload. There is deliberately no default — the old
     * {@code enunas-media} fallback pointed at a bucket that does not exist, which turned a missing
     * environment variable into a runtime failure per request instead of a boot failure.
     *
     * <p>{@code cdnBaseUrl} is intentionally *not* required: with no CloudFront in front of the
     * buckets, {@link MediaUrlResolver} builds direct S3 URLs from bucket + region.
     */
    @PostConstruct
    void validate() {
        requireBucket(buckets.getProduct(), "enunas.media.buckets.product", "S3_BUCKET_PRODUCTS");
        requireBucket(buckets.getBrand(), "enunas.media.buckets.brand", "S3_BUCKET_BRAND_PREVIEWS");
        // Region is not merely the S3 client's region: with no CDN it is part of the public URL
        // MediaUrlResolver builds ("https://<bucket>.s3.<region>.amazonaws.com/<key>"), where a
        // blank value yields a broken host with an empty label rather than any visible error.
        // A second hardcoded fallback here would only mask a cleared AWS_REGION — application.yaml
        // already carries the eu-central-1 default, so reaching this branch means it was overridden
        // with something empty, and guessing on the caller's behalf is the wrong answer.
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "enunas.media.region must be set (environment variable AWS_REGION). "
                            + "It is part of the public S3 URL, not just the client's region.");
        }
        // Both of these are pasted straight into a URL by MediaUrlResolver, so a value without a
        // scheme ("cdn.enunas.com") yields a relative path the browser resolves against the API
        // host — a 404 that looks like missing data, not like misconfiguration. Blank stays legal:
        // both are optional, and blank is the normal setting for each in the other's environment.
        requireAbsoluteUrl(cdnBaseUrl, "enunas.media.cdn-base-url", "MEDIA_CDN_BASE_URL");
        requireAbsoluteUrl(endpoint, "enunas.media.endpoint", "S3_ENDPOINT");
    }

    private static void requireAbsoluteUrl(String value, String property, String envVar) {
        if (value != null && !value.isBlank()
                && !value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalStateException(
                    property + " must start with http:// or https:// (environment variable "
                            + envVar + "), got: " + value);
        }
    }

    private static void requireBucket(String value, String property, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + " must be set (environment variable " + envVar + "). "
                            + "Media uploads and URLs cannot work without it.");
        }
    }
}
