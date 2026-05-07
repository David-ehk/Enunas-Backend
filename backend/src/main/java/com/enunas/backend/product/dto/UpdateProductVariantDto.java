package com.enunas.backend.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateProductVariantDto {

    private String color;

    private String size;

    @Min(0)
    private Integer stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
