package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;

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
    @Size(max = 100)
    @NoHtml
    private String carrier; // "DHL", "UPS", "Hermes" — free text, same convention as ShipmentConfirmationDto

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String trackingNumber;

    @NotBlank
    @Size(max = 2048)
    @URL
    private String labelUrl;
}
