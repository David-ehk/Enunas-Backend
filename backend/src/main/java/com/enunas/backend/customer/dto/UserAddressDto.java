package com.enunas.backend.customer.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Shared create/update request for a saved address. Every field is required on both paths — an
 * address is a value object, so update is a full replace, not a partial patch (unlike
 * {@code UpdateCustomerProfileDto} elsewhere in this codebase). {@code country} is intentionally
 * NOT restricted to {@code AllowedShippingCountries} here — see {@code UserAddress}'s javadoc.
 */
@Data
public class UserAddressDto {

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
    @Size(max = 16)
    @NoHtml
    private String postalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(min = 2, max = 2)
    @NoHtml
    private String country;
}
