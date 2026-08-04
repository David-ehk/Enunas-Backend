package com.enunas.backend.order.validation;

import com.enunas.backend.order.dto.CreateOrderDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ExactlyOneAddressSourceValidator implements ConstraintValidator<ExactlyOneAddressSource, CreateOrderDto> {

    @Override
    public boolean isValid(CreateOrderDto dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }
        boolean hasSaved = dto.getSavedAddressId() != null;
        boolean hasInline = dto.getShippingAddress() != null;
        if (hasSaved == hasInline) { // both true (both given) or both false (neither given)
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("shippingAddress")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
