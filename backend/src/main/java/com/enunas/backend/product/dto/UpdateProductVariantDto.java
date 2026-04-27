package com.enunas.backend.product.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateProductVariantDto {

    private String color;

    private String size;

    @Min(0)
    private Integer stockQuantity;

    @DecimalMin(value = "0.0")
    private BigDecimal weightGrams;
}
