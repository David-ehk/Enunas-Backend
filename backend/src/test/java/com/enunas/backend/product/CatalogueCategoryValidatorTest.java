package com.enunas.backend.product;

import com.enunas.backend.product.validation.CatalogueCategoryValidator;
import com.enunas.backend.product.validation.CatalogueCategoryAware;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class CatalogueCategoryValidatorTest {

    private CatalogueCategoryValidator validator;
    private ConstraintValidatorContext ctx;
    private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;
    private ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext nodeBuilder;

    @BeforeEach
    void setUp() {
        validator = new CatalogueCategoryValidator();
        ctx = mock(ConstraintValidatorContext.class);
        violationBuilder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
        nodeBuilder = mock(ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext.class);

        // disableDefaultConstraintViolation() is void — use doNothing
        doNothing().when(ctx).disableDefaultConstraintViolation();
        when(ctx.getDefaultConstraintMessageTemplate()).thenReturn("catalogueCategory must have 1 to 3 values when category is CLOTHING");
        when(ctx.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);
        when(violationBuilder.addPropertyNode(anyString())).thenReturn(nodeBuilder);
        // addConstraintViolation() returns ConstraintValidatorContext, not void
        when(nodeBuilder.addConstraintViolation()).thenReturn(ctx);
    }

    @Test
    void nonClothing_withNullCategory_isValid() {
        assertThat(validator.isValid(dto(ProductCategory.SHOES, null), ctx)).isTrue();
    }

    @Test
    void nonClothing_withAnyCategory_isValid() {
        assertThat(validator.isValid(dto(ProductCategory.ACCESSORIES,
                List.of(ProductCatalogueCategory.STREETWEAR)), ctx)).isTrue();
    }

    @Test
    void clothing_withOneCategory_isValid() {
        assertThat(validator.isValid(dto(ProductCategory.CLOTHING,
                List.of(ProductCatalogueCategory.STREETWEAR)), ctx)).isTrue();
    }

    @Test
    void clothing_withThreeCategories_isValid() {
        assertThat(validator.isValid(dto(ProductCategory.CLOTHING,
                List.of(ProductCatalogueCategory.STREETWEAR,
                        ProductCatalogueCategory.ATHLEISURE,
                        ProductCatalogueCategory.CULTURAL)), ctx)).isTrue();
    }

    @Test
    void clothing_withNullCatalogueCategory_isInvalid() {
        assertThat(validator.isValid(dto(ProductCategory.CLOTHING, null), ctx)).isFalse();
    }

    @Test
    void clothing_withEmptyCatalogueCategory_isInvalid() {
        assertThat(validator.isValid(dto(ProductCategory.CLOTHING, List.of()), ctx)).isFalse();
    }

    @Test
    void clothing_withFourCategories_isInvalid() {
        assertThat(validator.isValid(dto(ProductCategory.CLOTHING,
                List.of(ProductCatalogueCategory.STREETWEAR,
                        ProductCatalogueCategory.ATHLEISURE,
                        ProductCatalogueCategory.CULTURAL,
                        ProductCatalogueCategory.EXPERIMENTAL)), ctx)).isFalse();
    }

    @Test
    void nullDto_isValid() {
        assertThat(validator.isValid(null, ctx)).isTrue();
    }

    // ===== Helper =====

    private CatalogueCategoryAware dto(ProductCategory category, List<ProductCatalogueCategory> catalogueCategory) {
        return new CatalogueCategoryAware() {
            @Override public ProductCategory getCategory() { return category; }
            @Override public List<ProductCatalogueCategory> getCatalogueCategory() { return catalogueCategory; }
        };
    }
}
