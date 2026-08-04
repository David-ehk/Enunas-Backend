package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UpdateListingDto {

    /** When set, reinterprets the price figures under the new mode (recompute is automatic). */
    private PriceInputMode priceInputMode;

    @DecimalMin(value = "0.01")
    private BigDecimal price;

    @DecimalMin(value = "0.0")
    private BigDecimal discountPrice;

    private Boolean active;

    @Size(max = 100)
    @NoHtml
    private String region;

    private LocalDateTime dropDate;

    private LocalDateTime availableFrom;

    private LocalDateTime availableUntil;
}
