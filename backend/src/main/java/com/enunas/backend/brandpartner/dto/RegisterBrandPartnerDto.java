package com.enunas.backend.brandpartner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterBrandPartnerDto {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank
    @Size(max = 100)
    private String brandName;

    private String description;

    private String logoUrl;

    private String websiteUrl;

    private String instagramHandle;
}
