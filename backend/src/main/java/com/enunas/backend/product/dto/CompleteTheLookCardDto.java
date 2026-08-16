package com.enunas.backend.product.dto;

import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Lightweight card for "Complete The Look" references.
 * Never exposes the full product graph — prevents recursive serialization.
 *
 * {@code price} is the lowest active listing price for this product, or null when
 * no active listing exists. Populated by the service layer via
 * {@link #from(Product, BigDecimal)}; use {@link #from(Product)} when price is not needed.
 */
@Getter
@Builder
public class CompleteTheLookCardDto {

    private Long id;
    private String name;
    private String brandName;
    /** First primary image URL, or the first image if no primary is set, or null. */
    private String image;
    /** Lowest active listing price; null when no active listing exists. */
    private BigDecimal price;

    /** Convenience overload — price will be null. */
    public static CompleteTheLookCardDto from(Product product, MediaUrlResolver resolver) {
        return from(product, null, resolver);
    }

    public static CompleteTheLookCardDto from(Product product, BigDecimal lowestActivePrice, MediaUrlResolver resolver) {
        String firstImage = product.getImages().stream()
                .filter(img -> img.isPrimary())
                .findFirst()
                .map(img -> resolver.resolve(img.getStorageKey()))
                .orElseGet(() -> product.getImages().isEmpty()
                        ? null
                        : resolver.resolve(product.getImages().get(0).getStorageKey()));

        return CompleteTheLookCardDto.builder()
                .id(product.getId())
                .name(product.getName())
                .brandName(product.getBrand() != null ? product.getBrand().getBrandName() : null)
                .image(firstImage)
                .price(lowestActivePrice)
                .build();
    }
}
