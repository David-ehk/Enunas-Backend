package com.enunas.backend.order;

import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void notes_containingHtml_isInvalid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setNotes("<script>alert(document.cookie)</script>");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "notes".equals(v.getPropertyPath().toString()));
    }

    @Test
    void discountCode_withSpaces_isInvalid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setDiscountCode("not a code");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "discountCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void discountCode_alphanumeric_isValid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setDiscountCode("SUMMER-25");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).noneMatch(v -> "discountCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void cancelOrderNote_containingHtml_isInvalid() {
        CancelOrderDto dto = new CancelOrderDto();
        dto.setReason(CancelReason.CUSTOMER_REQUEST);
        dto.setNote("<b>urgent</b>");

        Set<ConstraintViolation<CancelOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "note".equals(v.getPropertyPath().toString()));
    }

    @Test
    void shipmentConfirmation_carrierContainingHtml_isInvalid() {
        ShipmentConfirmationDto dto = new ShipmentConfirmationDto();
        dto.setCarrier("<img src=x onerror=alert(1)>");
        dto.setTrackingNumber("1Z999AA10123456784");

        Set<ConstraintViolation<ShipmentConfirmationDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "carrier".equals(v.getPropertyPath().toString()));
    }

    private CreateOrderDto validCreateOrderDto() {
        CreateOrderDto dto = new CreateOrderDto();
        OrderItemRequestDto item = new OrderItemRequestDto();
        item.setListingId(1L);
        item.setQuantity(1);
        dto.setItems(List.of(item));

        ShippingAddressDto address = new ShippingAddressDto();
        address.setFirstName("Jane");
        address.setLastName("Doe");
        address.setStreet("Hauptstrasse");
        address.setHouseNumber("1");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("DE");
        dto.setShippingAddress(address);

        return dto;
    }
}
