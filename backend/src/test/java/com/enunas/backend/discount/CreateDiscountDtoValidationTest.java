package com.enunas.backend.discount;

import com.enunas.backend.discount.dto.CreateDiscountDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateDiscountDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validCode_hasNoViolations() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("SUMMER-25");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void code_containingHtml_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("<script>alert(1)</script>");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }

    @Test
    void code_tooShort_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("AB");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }

    @Test
    void code_withWhitespace_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("SUMMER 25");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }
}
