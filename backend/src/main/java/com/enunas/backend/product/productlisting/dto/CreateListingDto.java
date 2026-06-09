package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.PriceInputMode;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateListingDto {

    @NotNull
    private Long variantId;

    /** Whether {@code price}/{@code discountPrice} are entered as NET or GROSS figures. */
    @NotNull
    private PriceInputMode priceInputMode;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal price;

    @DecimalMin(value = "0.0")
    private BigDecimal discountPrice;

    @NotBlank
    @Size(min = 3, max = 3)
    private String currency = "EUR";

    private String region;

    private LocalDateTime dropDate;

    private LocalDateTime availableFrom;

    private LocalDateTime availableUntil;
}
