package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UpdateProductDto {

    private String name;

    private String description;

    private String inspirationStory;

    private ProductCategory category;

    private ProductCatalogueCategory catalogueCategory;

    private Gender gender;

    private String material;

    private String originCountry;

    private String careInstructions;

    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private Integer returnPeriodDays;
}
