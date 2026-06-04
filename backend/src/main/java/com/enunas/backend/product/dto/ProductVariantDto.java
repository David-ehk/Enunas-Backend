package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ProductVariantDto {

    @NotBlank
    private String color;

    @NotNull
    private ColorFamily colorFamily;

    @NotBlank
    private String size;

    @Min(0)
    private int stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
