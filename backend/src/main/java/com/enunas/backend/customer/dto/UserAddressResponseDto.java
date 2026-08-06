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

    // Field is named "defaultAddress" (not "isDefault") so its own implicit Jackson name and the
    // Lombok-generated getter's implicit name (isDefaultAddress() -> "defaultAddress") agree.
    // If the field were named "isDefault", Jackson would key it by "isDefault" (its own implicit
    // name) while ALSO keying the getter isDefault() by "default" (it strips the "is" prefix from
    // boolean getters) — two different implicit names that @JsonProperty on the field alone does
    // not merge, producing BOTH "default" and "isDefault" in the serialized JSON. The explicit
    // @JsonProperty below keeps the actual wire key as "isDefault" for the frontend contract.
    @JsonProperty("isDefault")
    private boolean defaultAddress;

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
                .defaultAddress(address.isDefault())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }
}
