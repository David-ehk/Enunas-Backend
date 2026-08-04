package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import com.enunas.backend.product.ProductType;
import com.enunas.backend.product.validation.CatalogueCategoryAware;
import com.enunas.backend.product.validation.ValidCatalogueCategory;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Data
@ValidCatalogueCategory
public class CreateProductDto implements CatalogueCategoryAware {

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String name;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 5000)
    @NoHtml
    private String inspirationStory;

    @NotNull
    private ProductCategory category;

    /**
     * Required when category == CLOTHING (1–3 values). Optional otherwise.
     * Validated by {@link com.enunas.backend.product.validation.CatalogueCategoryValidator}.
     */
    private List<ProductCatalogueCategory> catalogueCategory;

    @NotNull
    private ProductType productType;

    @NotNull
    private Gender gender;

    @Size(max = 255)
    @NoHtml
    private String material;

    @Size(max = 100)
    @NoHtml
    private String originCountry;

    @Size(max = 2000)
    @NoHtml
    private String careInstructions;

    @Size(max = 255)
    @NoHtml
    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private int returnPeriodDays = 14;

    @NotEmpty
    @Valid
    private List<ProductVariantDto> variants;

    /** If true, completeTheLookProductIds must contain 1–4 distinct product IDs. */
    private Boolean completeTheLookEnabled = false;

    /** IDs of related products shown in "Complete The Look". Validated in service. */
    private Set<Long> completeTheLookProductIds;
}
