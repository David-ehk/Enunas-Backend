package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class CreateProductDto {

    @NotBlank
    private String name;

    private String description;

    private String inspirationStory;

    @NotNull
    private ProductCategory category;

    private ProductCatalogueCategory catalogueCategory;

    @NotNull
    private Gender gender;

    private String material;

    private String originCountry;

    private String careInstructions;

    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private int returnPeriodDays = 14;

    @NotEmpty
    @Valid
    private List<ProductVariantDto> variants;
}
