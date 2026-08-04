package com.enunas.backend.order;

import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.order.dto.UploadReturnLabelDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void returnRequest_descriptionContainingHtml_isInvalid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_SIZE, "<script>x</script>");

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void returnRequest_plainDescription_isValid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_SIZE, "Wrong size shipped");

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void shippingProblem_descriptionContainingHtml_isInvalid() {
        ShippingProblemDto dto = new ShippingProblemDto();
        dto.setDescription("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<ShippingProblemDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void uploadReturnLabel_labelUrlNotAUrl_isInvalid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("DHL");
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl("not-a-url");

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "labelUrl".equals(v.getPropertyPath().toString()));
    }

    @Test
    void uploadReturnLabel_validHttpsUrl_isValid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("DHL");
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl("https://labels.example.com/abc123.pdf");

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void returnRequest_descriptionAtMaxLength_isValid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_SIZE, "a".repeat(500));

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void returnRequest_descriptionExceedsMaxLength_isInvalid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_SIZE, "a".repeat(501));

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void uploadReturnLabel_carrierAndLabelUrlAtMaxLength_isValid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("a".repeat(64));
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl(validUrlOfLength(500));

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void uploadReturnLabel_carrierAndLabelUrlExceedMaxLength_isInvalid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("a".repeat(65));
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl(validUrlOfLength(501));

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "carrier".equals(v.getPropertyPath().toString()));
        assertThat(violations).anyMatch(v -> "labelUrl".equals(v.getPropertyPath().toString()));
    }

    /** Builds a syntactically valid https URL of exactly {@code length} characters. */
    private static String validUrlOfLength(int length) {
        String prefix = "https://a.co/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
