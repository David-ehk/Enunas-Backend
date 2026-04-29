package com.enunas.backend.customer.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/** Partial-update DTO. Any null field is left unchanged on the server. */
@Data
public class UpdateCustomerProfileDto {

    private String firstName;
    private String lastName;
    private String username;
    private String profileImageUrl;

    @Size(min = 2, max = 2)
    private String country;
    private String city;

    private String preferredSizeTop;
    private String preferredSizeBottom;
    private String preferredSizeShoes;

    @Positive
    private Integer heightCm;

    @Positive
    private Integer weightKg;

    private List<String> preferredStyles;
    private List<String> favoriteBrands;
    private List<String> favoriteCategories;
}
