package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The only place in the codebase that knows how a stored key becomes a public URL. */
@Component
@RequiredArgsConstructor
public class MediaUrlResolver {

    private final MediaStorageProperties properties;

    /**
     * Returns {@code null} for a null/blank key (e.g. an optional brand logo/hero that was never
     * set) so DTOs never emit a broken "base/null" URL.
     *
     * <p>With no CDN configured the URL points straight at the object's own bucket, which is why
     * the bucket has to be derived from the key here — the two media buckets have different
     * hostnames, and callers (DTO mappers) only ever hold a key. Both buckets are publicly
     * readable; see docs/aws-media-setup.md for the bucket policy this relies on.
     */
    public String resolve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return stripTrailingSlash(baseFor(key)) + "/" + key;
    }

    private String baseFor(String key) {
        String cdn = properties.getCdnBaseUrl();
        if (cdn != null && !cdn.isBlank()) {
            return cdn;
        }
        String bucket = properties.bucketForKey(key);
        String endpoint = properties.getEndpoint();
        if (endpoint != null && !endpoint.isBlank()) {
            // LocalStack/S3Mock: path-style, matching the S3Client's pathStyleAccessEnabled.
            return stripTrailingSlash(endpoint) + "/" + bucket;
        }
        return "https://" + bucket + ".s3." + properties.getRegion() + ".amazonaws.com";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
