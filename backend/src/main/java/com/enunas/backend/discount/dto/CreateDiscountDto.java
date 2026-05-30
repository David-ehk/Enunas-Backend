package com.enunas.backend.discount.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Create payload shared by the admin and brand endpoints. The discount {@code type} is never
 * read from the client — it is fixed by the endpoint (ADMIN vs BRAND), so a brand can never
 * mint an admin-absorbed code. The per-type percent cap is enforced server-side.
 */
@Data
public class CreateDiscountDto {

    @NotBlank
    private String code;

    /** Discount fraction, e.g. 0.10 for 10%. Upper bound checked per type in the service. */
    @NotNull
    @DecimalMin(value = "0.0001")
    @DecimalMax(value = "1.0000")
    private BigDecimal percent;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    @Min(1)
    private Integer maxUses;
}
