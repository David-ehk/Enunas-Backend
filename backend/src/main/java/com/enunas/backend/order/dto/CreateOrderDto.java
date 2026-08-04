package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

    @Size(max = 1000)
    @NoHtml
    private String notes;

    /** Optional single discount code (max one per order — no stacking). */
    @Size(max = 32)
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "must contain only letters, digits, - and _")
    private String discountCode;
}
