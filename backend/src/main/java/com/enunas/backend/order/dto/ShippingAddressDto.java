package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ShippingAddressDto {

    @NotBlank
    private String fullName;

    @NotBlank
    private String street;

    private String street2;

    @NotBlank
    private String city;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String country;

    private String state;

    private String phone;
}
