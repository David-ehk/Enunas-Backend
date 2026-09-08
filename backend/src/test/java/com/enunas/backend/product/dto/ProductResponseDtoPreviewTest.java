package com.enunas.backend.product.dto;

import com.enunas.backend.product.Product;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code preview} is derived from {@link Product#getReleaseDate()} at mapping time: a product whose
 * release date is still in the future is a "Coming Soon" preview — flagged, and with its price
 * withheld until launch. See docs/superpowers/specs/2026-09-08-product-preview-state-design.md.
 */
class ProductResponseDtoPreviewTest {

    private static final DisplayPrice PRICE =
            new DisplayPrice(new BigDecimal("89.00"), new BigDecimal("120.00"));

    private static Product productReleasingOn(LocalDate releaseDate) {
        return Product.builder()
                .creator(User.builder().build())
                .releaseDate(releaseDate)
                .build();
    }

    private static ProductResponseDto map(Product product) {
        return ProductResponseDto.from(product, PRICE, id -> null, null);
    }

    @Test
    void futureReleaseDate_isPreview_andWithholdsPrice() {
        ProductResponseDto dto = map(productReleasingOn(LocalDate.now().plusDays(1)));

        assertThat(dto.isPreview()).isTrue();
        assertThat(dto.getPrice()).isNull();
        assertThat(dto.getOriginalPrice()).isNull();
    }

    @Test
    void releaseDateToday_isNotPreview_andKeepsPrice() {
        ProductResponseDto dto = map(productReleasingOn(LocalDate.now()));

        assertThat(dto.isPreview()).isFalse();
        assertThat(dto.getPrice()).isEqualByComparingTo("89.00");
        assertThat(dto.getOriginalPrice()).isEqualByComparingTo("120.00");
    }

    @Test
    void nullReleaseDate_isNotPreview_andKeepsPrice() {
        ProductResponseDto dto = map(productReleasingOn(null));

        assertThat(dto.isPreview()).isFalse();
        assertThat(dto.getPrice()).isEqualByComparingTo("89.00");
    }
}
