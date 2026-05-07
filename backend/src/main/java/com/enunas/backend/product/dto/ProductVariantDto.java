package com.enunas.backend.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ProductVariantDto {

    @NotBlank
    private String color;

    @NotBlank
    private String size;

    @Min(0)
    private int stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
