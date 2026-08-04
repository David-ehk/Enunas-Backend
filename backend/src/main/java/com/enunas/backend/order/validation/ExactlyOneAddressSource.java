package com.enunas.backend.order.validation;

import com.enunas.backend.order.dto.CreateOrderDto;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ExactlyOneAddressSourceValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExactlyOneAddressSource {

    String message() default "exactly one of savedAddressId or shippingAddress must be provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
