package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.RejectionDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RejectionDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void reason_containingHtml_isInvalid() {
        RejectionDto dto = new RejectionDto();
        dto.setReason("<script>alert(1)</script>");

        Set<ConstraintViolation<RejectionDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "reason".equals(v.getPropertyPath().toString()));
    }

    @Test
    void plainTextReason_isValid() {
        RejectionDto dto = new RejectionDto();
        dto.setReason("Photos do not match the listed material.");

        Set<ConstraintViolation<RejectionDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }
}
