package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import com.enunas.backend.product.ProductType;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * All fields are optional — null means "keep current value".
 * CatalogueCategory rules (CLOTHING requires 1–3 values) are enforced in the service layer
 * because validation depends on the current persisted category when only one field is patched.
 */
@Data
public class UpdateProductDto {

    private String name;

    private String description;

    private String inspirationStory;

    private ProductCategory category;

    private List<ProductCatalogueCategory> catalogueCategory;

    private ProductType productType;

    private Gender gender;

    private String material;

    private String originCountry;

    private String careInstructions;

    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private Integer returnPeriodDays;

    private Boolean completeTheLookEnabled;

    private Set<Long> completeTheLookProductIds;
}
