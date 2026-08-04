package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserAddressDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validAddress_hasNoViolations() {
        UserAddressDto dto = validDto();

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void nonGermanCountry_isValid() {
        // UserAddress is a personal address book, not restricted to checkout's allowed-country
        // list -- a customer may save an address for a country not yet shippable.
        UserAddressDto dto = validDto();
        dto.setCountry("FR");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void nonGermanPostalCode_isValid() {
        UserAddressDto dto = validDto();
        dto.setCountry("NL");
        dto.setPostalCode("1012AB");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void firstName_containingHtml_isInvalid() {
        UserAddressDto dto = validDto();
        dto.setFirstName("<script>alert(1)</script>");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "firstName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void houseNumber_blank_isInvalid() {
        UserAddressDto dto = validDto();
        dto.setHouseNumber("");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "houseNumber".equals(v.getPropertyPath().toString()));
    }

    private UserAddressDto validDto() {
        UserAddressDto dto = new UserAddressDto();
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setStreet("Hauptstrasse");
        dto.setHouseNumber("1");
        dto.setPostalCode("10115");
        dto.setCity("Berlin");
        dto.setCountry("DE");
        return dto;
    }
}
