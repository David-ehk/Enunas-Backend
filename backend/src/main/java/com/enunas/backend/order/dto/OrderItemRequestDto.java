package com.enunas.backend.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OrderItemRequestDto {

    @NotNull
    private Long listingId;

    @NotNull
    @Min(1)
    private Integer quantity;
}
