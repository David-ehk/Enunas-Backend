package com.enunas.backend.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingAddress {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    private String street;

    @NotBlank
    @Column(name = "house_number")
    private String houseNumber;

    @Column(name = "street2")
    private String addressLine2;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String city;

    @NotBlank
    private String country;

    private String phone;
}
