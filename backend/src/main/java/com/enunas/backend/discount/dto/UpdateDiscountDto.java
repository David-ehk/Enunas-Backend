package com.enunas.backend.discount.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Partial update — only non-null fields are applied. {@code code} and {@code type} are immutable
 * (a code's absorption model must never change after creation). The percent cap is re-checked
 * per type in the service.
 */
@Data
public class UpdateDiscountDto {

    @DecimalMin(value = "0.0001")
    @DecimalMax(value = "1.0000")
    private BigDecimal percent;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    @Min(1)
    private Integer maxUses;

    private Boolean active;
}
