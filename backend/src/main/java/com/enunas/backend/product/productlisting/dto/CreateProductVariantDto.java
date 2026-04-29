package com.enunas.backend.product.productlisting.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateProductVariantDto {
    private String color;
    private String size;
    private Integer stockQuantity;
    private Integer weightGrams;
}
