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
    void firstName_containingHtml_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setFirstName("<script>alert(1)</script>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "firstName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void street_exceedsMaxLength_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setStreet("A".repeat(256));

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "street".equals(v.getPropertyPath().toString()));
    }

    @Test
    void houseNumber_withLetterSuffix_isValid() {
        ShippingAddressDto dto = validAddress();
        dto.setHouseNumber("12a");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void houseNumber_withDisallowedCharacter_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setHouseNumber("12<b>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "houseNumber".equals(v.getPropertyPath().toString()));
    }

    @Test
    void postalCode_fourDigits_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPostalCode("1234");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "postalCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void postalCode_withLetters_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPostalCode("1012AB");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "postalCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void country_notDE_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setCountry("NL");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "country".equals(v.getPropertyPath().toString()));
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
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setStreet("Hauptstrasse");
        dto.setHouseNumber("1");
        dto.setCity("Berlin");
        dto.setPostalCode("10115");
        dto.setCountry("DE");
        return dto;
    }
}
