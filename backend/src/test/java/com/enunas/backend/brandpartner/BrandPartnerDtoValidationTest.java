package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.dto.AdminBrandMasterDataDto;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BrandPartnerDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validRegisterDto_hasNoViolations() {
        RegisterBrandPartnerDto dto = validRegisterDto();

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void description_containingHtml_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setDescription("<script>alert(1)</script>");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void websiteUrl_notAUrl_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setWebsiteUrl("definitely not a url");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "websiteUrl".equals(v.getPropertyPath().toString()));
    }

    @Test
    void instagramHandle_withSpaces_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setInstagramHandle("not a handle!");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "instagramHandle".equals(v.getPropertyPath().toString()));
    }

    @Test
    void vatId_isNeverFormatValidated_onlyLengthCapped() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setVatId("not a real vat id ok!");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        // Free-text VAT id is accepted (manual check per BrandPartnerService) — no violation.
        assertThat(violations).isEmpty();
    }

    @Test
    void vatId_exceedsMaxLength_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setVatId("A".repeat(33));

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "vatId".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateDto_returnInstructions_containingHtml_isInvalid() {
        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setReturnInstructions("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<UpdateBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "returnInstructions".equals(v.getPropertyPath().toString()));
    }

    @Test
    void adminBrandMasterData_taxNumber_exceedsMaxLength_isInvalid() {
        AdminBrandMasterDataDto dto = new AdminBrandMasterDataDto();
        dto.setLegalName("Acme GmbH");
        dto.setAddressStreet("Musterstrasse 1");
        dto.setAddressPostalCode("10115");
        dto.setAddressCity("Berlin");
        dto.setAddressCountry("DE");
        dto.setTaxNumber("A".repeat(33));

        Set<ConstraintViolation<AdminBrandMasterDataDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "taxNumber".equals(v.getPropertyPath().toString()));
    }

    @Test
    void websiteUrl_boundaryLength_enforcesNewCap() {
        RegisterBrandPartnerDto dto = validRegisterDto();

        String atMax = validUrlOfLength(255);
        dto.setWebsiteUrl(atMax);
        assertThat(validator.validate(dto)).isEmpty();

        String overMax = validUrlOfLength(256);
        dto.setWebsiteUrl(overMax);
        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "websiteUrl".equals(v.getPropertyPath().toString()));
    }

    /** Builds a syntactically valid https URL of exactly {@code length} characters. */
    private static String validUrlOfLength(int length) {
        String prefix = "https://a.co/";
        return prefix + "a".repeat(length - prefix.length());
    }

    private RegisterBrandPartnerDto validRegisterDto() {
        RegisterBrandPartnerDto dto = new RegisterBrandPartnerDto();
        dto.setEmail("brand@example.com");
        dto.setPassword("supersecret1");
        dto.setBrandName("Acme");
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setLegalName("Acme GmbH");
        dto.setAddressStreet("Musterstrasse 1");
        dto.setAddressPostalCode("10115");
        dto.setAddressCity("Berlin");
        dto.setAddressCountry("DE");
        return dto;
    }
}
