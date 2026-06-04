package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateProductVariantDto {

    private String color;

    private ColorFamily colorFamily;

    private String size;

    @Min(0)
    private Integer stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
