package com.enunas.backend.order;

import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
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

class CreateOrderDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void inlineAddressOnly_isValid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(validAddress());
        dto.setSavedAddressId(null);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void savedAddressIdOnly_isValid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(null);
        dto.setSavedAddressId(42L);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void bothProvided_isInvalid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(validAddress());
        dto.setSavedAddressId(42L);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void neitherProvided_isInvalid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(null);
        dto.setSavedAddressId(null);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
    }

    private CreateOrderDto baseDto() {
        CreateOrderDto dto = new CreateOrderDto();
        OrderItemRequestDto item = new OrderItemRequestDto();
        item.setListingId(1L);
        item.setQuantity(1);
        dto.setItems(List.of(item));
        return dto;
    }

    private ShippingAddressDto validAddress() {
        ShippingAddressDto address = new ShippingAddressDto();
        address.setFirstName("Jane");
        address.setLastName("Doe");
        address.setStreet("Hauptstrasse");
        address.setHouseNumber("1");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("DE");
        return address;
    }
}
