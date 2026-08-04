package com.enunas.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects any value containing {@code <} or {@code >}. There is no rich-text field anywhere in
 * this backend today, so free-text input is never expected to carry markup — this constraint
 * blocks stored-XSS payloads at the API boundary instead of sanitizing/stripping them. Null and
 * empty values are always valid; combine with {@code @NotBlank}/{@code @NotNull} for presence.
 */
@Documented
@Constraint(validatedBy = NoHtmlValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoHtml {

    String message() default "must not contain HTML markup";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
