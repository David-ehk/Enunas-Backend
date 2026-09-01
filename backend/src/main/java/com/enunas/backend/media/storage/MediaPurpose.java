package com.enunas.backend.media.storage;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-purpose upload policy: where a key lives, what content types are allowed, and the max size.
 * Keys are always {@code <prefix><uuid>[suffix].<ext>} — server-generated, never client-supplied.
 */
public enum MediaPurpose {

    PRODUCT_IMAGE(Scope.PRODUCT, "products/%d/images/", "",
            imageTypes(), 10L * 1024 * 1024),
    PRODUCT_VIDEO(Scope.PRODUCT, "products/%d/videos/", "",
            Set.of("video/mp4", "video/webm"), 200L * 1024 * 1024),
    VIDEO_THUMB(Scope.PRODUCT, "products/%d/videos/", "-t",
            imageTypes(), 2L * 1024 * 1024),
    BRAND_LOGO(Scope.BRAND, "brands/%d/logo/", "",
            imageTypes(), 5L * 1024 * 1024),
    BRAND_HERO(Scope.BRAND, "brands/%d/hero/", "",
            imageTypes(), 10L * 1024 * 1024);

    /**
     * Which of the two media buckets a purpose lives in, and the key root that identifies it.
     * Every {@code keyPrefixTemplate} above starts with its scope's root — enforced by
     * {@code MediaPurposeTest}, since the two are written out separately — so a stored key alone is
     * enough to find its bucket again. That is what {@code MediaStorageService.delete} and
     * {@code MediaUrlResolver} rely on: both only ever receive a key, never a purpose.
     */
    public enum Scope {
        PRODUCT("products/"),
        BRAND("brands/");

        private final String keyRoot;

        Scope(String keyRoot) {
            this.keyRoot = keyRoot;
        }

        public String keyRoot() {
            return keyRoot;
        }

        /** Every key is server-generated from a {@code keyPrefixTemplate}, so exactly one root matches. */
        public static Scope fromKey(String key) {
            if (key != null) {
                for (Scope scope : values()) {
                    if (key.startsWith(scope.keyRoot)) {
                        return scope;
                    }
                }
            }
            throw new IllegalArgumentException("Key belongs to no known media scope: " + key);
        }
    }

    private static final Map<String, String> EXTENSIONS_BY_CONTENT_TYPE = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "video/mp4", "mp4",
            "video/webm", "webm"
    );

    private final Scope scope;
    private final String keyPrefixTemplate;
    private final String keySuffix;
    private final Set<String> allowedContentTypes;
    private final long maxBytes;

    MediaPurpose(Scope scope, String keyPrefixTemplate, String keySuffix,
                 Set<String> allowedContentTypes, long maxBytes) {
        this.scope = scope;
        this.keyPrefixTemplate = keyPrefixTemplate;
        this.keySuffix = keySuffix;
        this.allowedContentTypes = allowedContentTypes;
        this.maxBytes = maxBytes;
    }

    public Scope scope() {
        return scope;
    }

    public Set<String> allowedContentTypes() {
        return allowedContentTypes;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public String keyPrefix(long resourceId) {
        return keyPrefixTemplate.formatted(resourceId);
    }

    /** Rejects an upload request before any key is generated or any presigning happens. */
    public void validate(String contentType, long contentLength) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported content type '" + contentType
                    + "' for " + this + ". Allowed: " + allowedContentTypes);
        }
        if (contentLength <= 0 || contentLength > maxBytes) {
            throw new IllegalArgumentException("contentLength must be between 1 and " + maxBytes
                    + " bytes for " + this + ", got " + contentLength);
        }
    }

    /** Server-generated key: {@code <prefix><uuid><suffix>.<ext>}. Call {@link #validate} first. */
    public String generateKey(long resourceId, String contentType) {
        if (!allowedContentTypes.contains(contentType)) {
            throw new IllegalArgumentException("Unsupported content type '" + contentType
                    + "' for " + this + ". Allowed: " + allowedContentTypes);
        }
        String extension = EXTENSIONS_BY_CONTENT_TYPE.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("Unsupported content type: " + contentType);
        }
        return keyPrefix(resourceId) + UUID.randomUUID() + keySuffix + "." + extension;
    }

    private static Set<String> imageTypes() {
        return Set.of("image/jpeg", "image/png", "image/webp");
    }
}
