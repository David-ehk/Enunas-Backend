package com.enunas.backend.order;

import com.enunas.backend.order.dto.ShippingAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingAddressDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validAddress_hasNoViolations() {
        ShippingAddressDto dto = validAddress();

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void fullName_containingHtml_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setFullName("<script>alert(1)</script>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "fullName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void street_exceedsMaxLength_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setStreet("A".repeat(256));

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "street".equals(v.getPropertyPath().toString()));
    }

    @Test
    void phone_withLetters_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPhone("call-me-maybe");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "phone".equals(v.getPropertyPath().toString()));
    }

    @Test
    void phone_validFormat_isValid() {
        ShippingAddressDto dto = validAddress();
        dto.setPhone("+49 30 1234567");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    private ShippingAddressDto validAddress() {
        ShippingAddressDto dto = new ShippingAddressDto();
        dto.setFullName("Jane Doe");
        dto.setStreet("Hauptstrasse 1");
        dto.setCity("Berlin");
        dto.setPostalCode("10115");
        dto.setCountry("Germany");
        return dto;
    }
}
