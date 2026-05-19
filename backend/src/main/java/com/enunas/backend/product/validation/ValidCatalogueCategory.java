package com.enunas.backend.product.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * Class-level constraint: when category == CLOTHING, catalogueCategory must contain 1–3 values.
 * When category != CLOTHING, catalogueCategory is unconstrained (may be null or empty).
 */
@Documented
@Constraint(validatedBy = CatalogueCategoryValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCatalogueCategory {

    String message() default "catalogueCategory must have 1 to 3 values when category is CLOTHING";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
