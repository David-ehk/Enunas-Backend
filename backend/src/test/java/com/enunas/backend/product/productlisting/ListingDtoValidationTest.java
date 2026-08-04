package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ListingDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validCreateListingDto_hasNoViolations() {
        CreateListingDto dto = validDto();

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void lowercaseCurrency_isInvalid() {
        CreateListingDto dto = validDto();
        dto.setCurrency("eur");

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "currency".equals(v.getPropertyPath().toString()));
    }

    @Test
    void region_containingHtml_isInvalid() {
        CreateListingDto dto = validDto();
        dto.setRegion("<script>alert(1)</script>");

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "region".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateListingDto_region_exceedsMaxLength_isInvalid() {
        UpdateListingDto dto = new UpdateListingDto();
        dto.setRegion("A".repeat(101));

        Set<ConstraintViolation<UpdateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "region".equals(v.getPropertyPath().toString()));
    }

    private CreateListingDto validDto() {
        CreateListingDto dto = new CreateListingDto();
        dto.setVariantId(1L);
        dto.setPriceInputMode(com.enunas.backend.product.productlisting.PriceInputMode.NET);
        dto.setPrice(new BigDecimal("19.99"));
        dto.setCurrency("EUR");
        return dto;
    }
}
