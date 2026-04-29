package com.enunas.backend.brandpartner.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateBrandPartnerDto {

    private String description;

    private String logoUrl;

    private String websiteUrl;

    private String instagramHandle;

    private String tiktokHandle;

    @Size(min = 2, max = 2)
    private String country;

    @Email
    private String contactEmail;
}
