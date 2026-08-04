package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShipmentConfirmationDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String carrier;       // "DHL", "UPS", "Hermes"

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String trackingNumber;

    @Size(max = 500)
    @NoHtml
    private String note;          // optional
}
