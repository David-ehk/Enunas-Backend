package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ProductVariant;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ProductVariantResponseDto {

    private Long id;
    private String sku;
    private String color;
    private String size;
    private int stockQuantity;
    private BigDecimal weightGrams;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductVariantResponseDto from(ProductVariant variant) {
        return ProductVariantResponseDto.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .color(variant.getColor())
                .size(variant.getSize())
                .stockQuantity(variant.getStockQuantity())
                .weightGrams(variant.getWeightGrams())
                .createdAt(variant.getCreatedAt())
                .updatedAt(variant.getUpdatedAt())
                .build();
    }
}
