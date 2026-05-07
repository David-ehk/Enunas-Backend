package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ProductVariant;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ProductVariantResponseDto {

    private Long id;
    private String sku;
    private String color;
    private String size;
    private int stockQuantity;
    private Integer weightGrams;

    public static ProductVariantResponseDto from(ProductVariant variant) {
        return ProductVariantResponseDto.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .color(variant.getColor())
                .size(variant.getSize())
                .stockQuantity(variant.getStockQuantity())
                .weightGrams(variant.getWeightGrams())
                .build();
    }
}
