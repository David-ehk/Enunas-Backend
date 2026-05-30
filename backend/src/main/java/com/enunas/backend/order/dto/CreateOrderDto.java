package com.enunas.backend.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderDto {

    @NotEmpty
    @Valid
    private List<OrderItemRequestDto> items;

    @NotNull
    @Valid
    private ShippingAddressDto shippingAddress;

    private String notes;

    /** Optional single discount code (max one per order — no stacking). */
    private String discountCode;
}
