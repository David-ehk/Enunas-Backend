package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.ProductListing;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ListingResponseDto {

    private Long id;
    private Long productId;
    private String productName;
    private Long variantId;
    private String variantSku;
    private String variantColor;
    private String variantSize;
    private BigDecimal price;
    private BigDecimal discountPrice;
    private BigDecimal shippingCost;
    private String currency;
    private int stock;
    private boolean active;
    private String region;
    private LocalDateTime dropDate;
    private LocalDateTime availableFrom;
    private LocalDateTime availableUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ListingResponseDto from(ProductListing productListing) {
        return ListingResponseDto.builder()
                .id(productListing.getId())
                .productId(productListing.getProduct().getId())
                .productName(productListing.getProduct().getName())
                .variantId(productListing.getVariant().getId())
                .variantSku(productListing.getVariant().getSku())
                .variantColor(productListing.getVariant().getColor())
                .variantSize(productListing.getVariant().getSize())
                .price(productListing.getPrice())
                .discountPrice(productListing.getDiscountPrice())
                .shippingCost(productListing.getShippingCost())
                .currency(productListing.getCurrency())
                .stock(productListing.getStock())
                .active(productListing.isActive())
                .region(productListing.getRegion())
                .dropDate(productListing.getDropDate())
                .availableFrom(productListing.getAvailableFrom())
                .availableUntil(productListing.getAvailableUntil())
                .createdAt(productListing.getCreatedAt())
                .updatedAt(productListing.getUpdatedAt())
                .build();
    }
}
