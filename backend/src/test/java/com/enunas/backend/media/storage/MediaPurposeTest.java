package com.enunas.backend.media.storage;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaPurposeTest {

    @Test
    void productImage_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.keyPrefix(42)).isEqualTo("products/42/images/");
    }

    @Test
    void productVideo_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.PRODUCT_VIDEO.keyPrefix(42)).isEqualTo("products/42/videos/");
    }

    @Test
    void videoThumb_sharesPrefixWithProductVideo_butSuffixesTheFilename() {
        assertThat(MediaPurpose.VIDEO_THUMB.keyPrefix(42)).isEqualTo("products/42/videos/");
        String key = MediaPurpose.VIDEO_THUMB.generateKey(42, "image/jpeg");
        assertThat(key).matches("products/42/videos/[0-9a-f-]{36}-t\\.jpg");
    }

    @Test
    void brandLogo_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.BRAND_LOGO.keyPrefix(7)).isEqualTo("brands/7/logo/");
    }

    @Test
    void brandHero_keyPrefix_matchesSpecPattern() {
        assertThat(MediaPurpose.BRAND_HERO.keyPrefix(7)).isEqualTo("brands/7/hero/");
    }

    @Test
    void generateKey_noSuffix_hasNoDashTBeforeExtension() {
        String key = MediaPurpose.PRODUCT_IMAGE.generateKey(42, "image/png");
        assertThat(key).matches("products/42/images/[0-9a-f-]{36}\\.png");
    }

    @Test
    void generateKey_resolvesExtensionFromContentType() {
        assertThat(MediaPurpose.PRODUCT_VIDEO.generateKey(1, "video/mp4")).endsWith(".mp4");
        assertThat(MediaPurpose.PRODUCT_VIDEO.generateKey(1, "video/webm")).endsWith(".webm");
    }

    @Test
    void generateKey_contentTypeNotAllowedForThisPurpose_throws() {
        assertThatThrownBy(() -> MediaPurpose.VIDEO_THUMB.generateKey(1, "video/mp4"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopes_areAssignedCorrectly() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.PRODUCT_VIDEO.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.VIDEO_THUMB.scope()).isEqualTo(MediaPurpose.Scope.PRODUCT);
        assertThat(MediaPurpose.BRAND_LOGO.scope()).isEqualTo(MediaPurpose.Scope.BRAND);
        assertThat(MediaPurpose.BRAND_HERO.scope()).isEqualTo(MediaPurpose.Scope.BRAND);
    }

    @Test
    void maxBytes_matchSpecTable() {
        assertThat(MediaPurpose.PRODUCT_IMAGE.maxBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(MediaPurpose.PRODUCT_VIDEO.maxBytes()).isEqualTo(200L * 1024 * 1024);
        assertThat(MediaPurpose.VIDEO_THUMB.maxBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(MediaPurpose.BRAND_LOGO.maxBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(MediaPurpose.BRAND_HERO.maxBytes()).isEqualTo(10L * 1024 * 1024);
    }

    @Test
    void validate_disallowedContentType_throws() {
        assertThatThrownBy(() -> MediaPurpose.PRODUCT_IMAGE.validate("application/pdf", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_oversize_throws() {
        assertThatThrownBy(() ->
                MediaPurpose.PRODUCT_IMAGE.validate("image/jpeg", 11L * 1024 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_zeroOrNegativeLength_throws() {
        assertThatThrownBy(() -> MediaPurpose.PRODUCT_IMAGE.validate("image/jpeg", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MediaPurpose.PRODUCT_IMAGE.validate("image/jpeg", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validate_allowedTypeWithinLimit_doesNotThrow() {
        MediaPurpose.PRODUCT_IMAGE.validate("image/webp", 1024);
    }

    // ===== Scope: a stored key must always lead back to its bucket =====

    /**
     * The key prefixes and the scope roots are written out separately, so nothing but this test
     * stops them drifting apart. If they ever did, {@code Scope.fromKey} would route a key to the
     * wrong bucket — or to none — and deletes and image URLs would silently break.
     */
    @Test
    void everyPurposeKeyStartsWithItsScopeRoot() {
        for (MediaPurpose purpose : MediaPurpose.values()) {
            String key = purpose.generateKey(42L, purpose.allowedContentTypes().iterator().next());

            assertThat(key).startsWith(purpose.scope().keyRoot());
            assertThat(MediaPurpose.Scope.fromKey(key)).isEqualTo(purpose.scope());
        }
    }

    @Test
    void fromKey_unknownPrefix_throws() {
        assertThatThrownBy(() -> MediaPurpose.Scope.fromKey("elsewhere/1/abc.jpg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no known media scope");
        assertThatThrownBy(() -> MediaPurpose.Scope.fromKey(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
