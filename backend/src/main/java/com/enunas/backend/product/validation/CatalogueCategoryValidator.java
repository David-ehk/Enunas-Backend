package com.enunas.backend.product.validation;

import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.List;

/**
 * Validates that catalogueCategory has 1–3 values when category == CLOTHING,
 * and is unconstrained otherwise.
 *
 * Applies to any object that exposes getCategory() and getCatalogueCategory().
 * Implemented against CreateProductDto (create path). Update path is validated in the service.
 */
public class CatalogueCategoryValidator implements ConstraintValidator<ValidCatalogueCategory, CatalogueCategoryAware> {

    @Override
    public boolean isValid(CatalogueCategoryAware dto, ConstraintValidatorContext ctx) {
        if (dto == null || dto.getCategory() != ProductCategory.CLOTHING) {
            return true;
        }
        List<ProductCatalogueCategory> cc = dto.getCatalogueCategory();
        if (cc == null || cc.isEmpty() || cc.size() > 3) {
            ctx.disableDefaultConstraintViolation();
            ctx.buildConstraintViolationWithTemplate(ctx.getDefaultConstraintMessageTemplate())
               .addPropertyNode("catalogueCategory")
               .addConstraintViolation();
            return false;
        }
        return true;
    }
}
