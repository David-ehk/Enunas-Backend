package com.enunas.backend.customer.dto;

import com.enunas.backend.customer.UserAddress;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserAddressResponseDto {

    private Long id;
    private String firstName;
    private String lastName;
    private String street;
    private String houseNumber;
    private String addressLine2;
    private String postalCode;
    private String city;
    private String country;

    // Explicit @JsonProperty: Jackson would otherwise serialize a Lombok-generated isDefault()
    // getter as JSON key "default" (it strips the "is" prefix from boolean getters by default),
    // not the "isDefault" the frontend expects.
    @JsonProperty("isDefault")
    private boolean isDefault;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserAddressResponseDto from(UserAddress address) {
        return UserAddressResponseDto.builder()
                .id(address.getId())
                .firstName(address.getFirstName())
                .lastName(address.getLastName())
                .street(address.getStreet())
                .houseNumber(address.getHouseNumber())
                .addressLine2(address.getAddressLine2())
                .postalCode(address.getPostalCode())
                .city(address.getCity())
                .country(address.getCountry())
                .isDefault(address.isDefault())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }
}
