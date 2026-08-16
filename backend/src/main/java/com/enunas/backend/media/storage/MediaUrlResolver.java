package com.enunas.backend.media.storage;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The only place in the codebase that knows the CDN host. */
@Component
@RequiredArgsConstructor
public class MediaUrlResolver {

    private final MediaStorageProperties properties;

    /**
     * Returns {@code null} for a null/blank key (e.g. an optional brand logo/hero that was never
     * set) so DTOs never emit a broken "cdnBase/null" URL.
     */
    public String resolve(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String base = properties.getCdnBaseUrl();
        if (base != null && base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/" + key;
    }
}
