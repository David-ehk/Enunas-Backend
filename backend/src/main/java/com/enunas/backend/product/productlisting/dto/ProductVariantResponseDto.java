package com.enunas.backend.product.productlisting.dto;

import lombok.Builder;
import lombok.Data;
import com.enunas.backend.product.productvariant.ProductVariant;

@Data
@Builder
public class ProductVariantResponseDto {
    private Long id;
    private String sku;
    private String color;
    private String size;
    private Integer stockQuantity;
    private Integer weightGrams;
    private Long productId;

    public static ProductVariantResponseDto from(ProductVariant variant) {
        return ProductVariantResponseDto.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .color(variant.getColor())
                .size(variant.getSize())
                .stockQuantity(variant.getStockQuantity())
                .weightGrams(variant.getWeightGrams())
                .productId(variant.getProduct().getId())
                .build();
    }
}
