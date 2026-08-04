package com.enunas.backend.product;

import com.enunas.backend.product.dto.UpdateProductDto;
import com.enunas.backend.product.dto.UpdateProductVariantDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateProductDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void allFieldsNull_isValid() {
        UpdateProductDto dto = new UpdateProductDto();

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void name_containingHtml_isInvalid() {
        UpdateProductDto dto = new UpdateProductDto();
        dto.setName("<b>Hoodie</b>");

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "name".equals(v.getPropertyPath().toString()));
    }

    @Test
    void collectionName_exceedsMaxLength_isInvalid() {
        UpdateProductDto dto = new UpdateProductDto();
        dto.setCollectionName("A".repeat(256));

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "collectionName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void variantColor_containingHtml_isInvalid() {
        UpdateProductVariantDto dto = new UpdateProductVariantDto();
        dto.setColor("<script>x</script>");

        Set<ConstraintViolation<UpdateProductVariantDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "color".equals(v.getPropertyPath().toString()));
    }
}
