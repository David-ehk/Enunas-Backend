package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import com.enunas.backend.product.ProductStatus;
import com.enunas.backend.product.ProductType;
import com.enunas.backend.validation.NoHtml;
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

    @Size(max = 255)
    @NoHtml
    private String name;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 5000)
    @NoHtml
    private String inspirationStory;

    private ProductCategory category;

    private List<ProductCatalogueCategory> catalogueCategory;

    private ProductType productType;

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
    private Integer returnPeriodDays;

    private Boolean completeTheLookEnabled;

    private Set<Long> completeTheLookProductIds;

    /**
     * Optional lifecycle change. A brand may set ACTIVE, INACTIVE or ARCHIVED — see
     * {@code ProductService.applyBrandStatusChange}, which also refuses to reactivate a product an
     * admin suspended or rejected. The admin route ({@code AdminService.updateProduct}) accepts any
     * status. Absent this field, the ARCHIVED that {@code deleteProduct}'s own 409 recommends was
     * rejected as an unknown property.
     */
    private ProductStatus status;
}
