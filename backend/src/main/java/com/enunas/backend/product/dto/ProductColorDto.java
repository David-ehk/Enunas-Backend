package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import lombok.Builder;
import lombok.Getter;

/** A colourway of a product: the swatch source for the PDP and the image-colour picker. */
@Getter
@Builder
public class ProductColorDto {

    private Long id;
    private String color;
    private ColorFamily colorFamily;
    private String sku;

    public static ProductColorDto from(ProductColor colour) {
        return ProductColorDto.builder()
                .id(colour.getId())
                .color(colour.getColor())
                .colorFamily(colour.getColorFamily())
                .sku(colour.getSku())
                .build();
    }
}
