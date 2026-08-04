package com.enunas.backend.order.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidShippingCountryValidator implements ConstraintValidator<ValidShippingCountry, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return AllowedShippingCountries.isAllowed(value);
    }
}
