package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShipmentConfirmationDto {

    @NotBlank
    private String carrier;       // "DHL", "UPS", "Hermes"

    @NotBlank
    private String trackingNumber;

    private String note;          // optional
}
