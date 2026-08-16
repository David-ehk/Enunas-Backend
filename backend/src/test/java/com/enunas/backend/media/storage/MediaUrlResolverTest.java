package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MediaUrlResolverTest {

    private MediaUrlResolver resolver(String cdnBaseUrl) {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl(cdnBaseUrl);
        return new MediaUrlResolver(properties);
    }

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
}
