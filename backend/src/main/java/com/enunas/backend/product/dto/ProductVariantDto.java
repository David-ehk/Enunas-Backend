package com.enunas.backend.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductVariantDto {

    @NotBlank
    private String color;

    @NotBlank
    private String size;

    @Min(0)
    private int stockQuantity;

    @DecimalMin(value = "0.0")
    private BigDecimal weightGrams;
}
