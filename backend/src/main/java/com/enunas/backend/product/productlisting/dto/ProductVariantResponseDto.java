package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductVariant;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductVariantResponseDto {
    private Long id;
    private String sku;
    private String color;
    private ColorFamily colorFamily;
    private String size;
    private Integer stockQuantity;
    private Integer weightGrams;
    private Long productId;
    private Long colorId;

    public static ProductVariantResponseDto from(ProductVariant variant) {
        return ProductVariantResponseDto.builder()
                .id(variant.getId())
                .sku(variant.getSku())
                .color(variant.getColor())
                .colorFamily(variant.getColorFamily())
                .size(variant.getSize())
                .stockQuantity(variant.getStockQuantity())
                .weightGrams(variant.getWeightGrams())
                .productId(variant.getProduct().getId())
                .colorId(variant.getProductColor() != null ? variant.getProductColor().getId() : null)
                .build();
    }
}
