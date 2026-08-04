package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ProductVariantDto {

    @NotBlank
    @Size(max = 50)
    @NoHtml
    private String color;

    @NotNull
    private ColorFamily colorFamily;

    @NotBlank
    @Size(max = 50)
    @NoHtml
    private String size;

    @Min(0)
    private int stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
