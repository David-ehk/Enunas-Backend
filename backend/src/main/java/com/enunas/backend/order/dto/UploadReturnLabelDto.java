package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Brand-supplied return label — the MVP path (no carrier-API integration exists yet). The brand
 * generates the label itself (its own DHL/UPS/Hermes account) and gives the platform the tracking
 * number and a URL to the label file/PDF; nothing here calls out to a carrier.
 */
@Getter
@Setter
@NoArgsConstructor
public class UploadReturnLabelDto {

    @NotBlank
    private String carrier; // "DHL", "UPS", "Hermes" — free text, same convention as ShipmentConfirmationDto

    @NotBlank
    private String trackingNumber;

    @NotBlank
    private String labelUrl;
}
