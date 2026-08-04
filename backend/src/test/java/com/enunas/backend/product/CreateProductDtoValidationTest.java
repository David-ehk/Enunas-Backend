package com.enunas.backend.product;

import com.enunas.backend.product.dto.CreateProductDto;
import com.enunas.backend.product.dto.ProductVariantDto;
import com.enunas.backend.product.productvariant.ColorFamily;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level validation test: uses the real Jakarta Validator (Hibernate Validator)
 * to confirm that the class-level {@code @ValidCatalogueCategory} constraint is triggered
 * when Spring processes {@code @Valid @RequestBody CreateProductDto} in the controller.
 *
 * This bypasses the need to stand up a full MVC context while still exercising the exact
 * constraint pipeline that Spring Boot wires up.
 */
class CreateProductDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ===== @ValidCatalogueCategory — class-level constraint =====

    @Test
    void clothing_nullCatalogueCategory_producesViolationOnCatalogueCategory() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(null);

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    @Test
    void clothing_emptyCatalogueCategory_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(List.of());

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    @Test
    void clothing_fourCatalogueCategories_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(List.of(
                ProductCatalogueCategory.STREETWEAR,
                ProductCatalogueCategory.ATHLEISURE,
                ProductCatalogueCategory.CULTURAL,
                ProductCatalogueCategory.EXPERIMENTAL));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    @Test
    void clothing_oneCatalogueCategory_isValid() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(List.of(ProductCatalogueCategory.STREETWEAR));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        // Only violations should be from other fields (none if all valid)
        assertThat(violations).noneMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    @Test
    void clothing_threeCatalogueCategories_isValid() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(List.of(
                ProductCatalogueCategory.STREETWEAR,
                ProductCatalogueCategory.ATHLEISURE,
                ProductCatalogueCategory.CULTURAL));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).noneMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    @Test
    void nonClothing_nullCatalogueCategory_isValid() {
        CreateProductDto dto = validClothingDto();
        dto.setCategory(ProductCategory.SHOES);
        dto.setCatalogueCategory(null);

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).noneMatch(v ->
            "catalogueCategory".equals(v.getPropertyPath().toString()));
    }

    // ===== @NotNull on productType =====

    @Test
    void nullProductType_producesViolation() {
        CreateProductDto dto = validClothingDto();
        dto.setProductType(null);

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v ->
            "productType".equals(v.getPropertyPath().toString()));
    }

    @Test
    void validProductType_noViolationOnThatField() {
        CreateProductDto dto = validClothingDto();
        dto.setProductType(ProductType.HOODIE);

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).noneMatch(v ->
            "productType".equals(v.getPropertyPath().toString()));
    }

    // ===== Fully valid DTO =====

    @Test
    void fullyValidDto_hasNoViolations() {
        CreateProductDto dto = validClothingDto();
        dto.setCatalogueCategory(List.of(ProductCatalogueCategory.STREETWEAR));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    // ===== @Size / @NoHtml on free-text fields =====

    @Test
    void name_exceedsMaxLength_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setName("A".repeat(256));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "name".equals(v.getPropertyPath().toString()));
    }

    @Test
    void description_containingHtml_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setDescription("<script>alert(1)</script>");

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void careInstructions_exceedsMaxLength_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setCareInstructions("A".repeat(2001));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "careInstructions".equals(v.getPropertyPath().toString()));
    }

    @Test
    void variantColor_containingHtml_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.getVariants().get(0).setColor("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("color"));
    }

    // ===== Helper =====

    private CreateProductDto validClothingDto() {
        CreateProductDto dto = new CreateProductDto();
        dto.setName("Test Hoodie");
        dto.setCategory(ProductCategory.CLOTHING);
        dto.setProductType(ProductType.HOODIE);
        dto.setGender(Gender.UNISEX);
        dto.setCatalogueCategory(List.of(ProductCatalogueCategory.STREETWEAR));

        ProductVariantDto variant = new ProductVariantDto();
        variant.setColor("Black");
        variant.setColorFamily(ColorFamily.BLACK);
        variant.setSize("M");
        variant.setStockQuantity(10);
        dto.setVariants(List.of(variant));

        return dto;
    }
}
