package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShippingAddressDto {

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String fullName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String street;

    @Size(max = 255)
    @NoHtml
    private String street2;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String postalCode;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String country;

    @Size(max = 100)
    @NoHtml
    private String state;

    @Size(max = 30)
    @Pattern(regexp = "^[+0-9 ()-]*$", message = "must be a valid phone number")
    private String phone;
}
