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

    private String bucket;
    private String region;
    private String cdnBaseUrl;
    /** Empty = real AWS. Set (e.g. LocalStack/S3Mock) = endpoint override + path-style access. */
    private String endpoint;
    private Duration presignTtl;

    /**
     * Fail-fast: real AWS (no endpoint override) configured with no CDN base URL would silently
     * resolve every stored key to a broken "cdnBase/key" URL with a blank host. LocalStack/S3Mock
     * dev/test runs are exempt — {@code endpoint} being set is what marks a non-production target.
     */
    @PostConstruct
    void validate() {
        boolean realAws = endpoint == null || endpoint.isBlank();
        boolean noCdn = cdnBaseUrl == null || cdnBaseUrl.isBlank();
        if (realAws && noCdn) {
            throw new IllegalStateException(
                    "enunas.media.cdn-base-url must be set when enunas.media.endpoint is empty " +
                    "(real AWS with no CDN would silently emit broken image URLs). " +
                    "Set MEDIA_CDN_BASE_URL for production, or S3_ENDPOINT for LocalStack/S3Mock dev/test.");
        }
    }
}
