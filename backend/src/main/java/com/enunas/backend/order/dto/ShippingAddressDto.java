package com.enunas.backend.order.dto;

import com.enunas.backend.order.validation.ValidShippingCountry;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShippingAddressDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String lastName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String street;

    @NotBlank
    @Size(max = 16)
    @Pattern(regexp = "^[A-Za-z0-9 /-]{1,16}$", message = "must be a valid house number")
    private String houseNumber;

    @Size(max = 255)
    @NoHtml
    private String addressLine2;

    @NotBlank
    @Pattern(regexp = "^\\d{5}$", message = "must be a 5-digit German postal code")
    private String postalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(min = 2, max = 2)
    @ValidShippingCountry
    private String country;

    @Size(max = 30)
    @Pattern(regexp = "^[+0-9 ()-]*$", message = "must be a valid phone number")
    private String phone;

    public static ShippingAddressDto from(com.enunas.backend.customer.UserAddress address) {
        ShippingAddressDto dto = new ShippingAddressDto();
        dto.setFirstName(address.getFirstName());
        dto.setLastName(address.getLastName());
        dto.setStreet(address.getStreet());
        dto.setHouseNumber(address.getHouseNumber());
        dto.setAddressLine2(address.getAddressLine2());
        dto.setPostalCode(address.getPostalCode());
        dto.setCity(address.getCity());
        dto.setCountry(address.getCountry());
        return dto;
    }
}
