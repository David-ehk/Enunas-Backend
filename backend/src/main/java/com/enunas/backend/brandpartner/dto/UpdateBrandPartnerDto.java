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

    private String vatId;

    private String taxNumber;

    // §22f supplier legal name + address — optional on update (null = keep current).
    @Size(max = 255)
    private String legalName;

    @Size(max = 255)
    private String addressStreet;

    @Size(max = 16)
    private String addressPostalCode;

    @Size(max = 128)
    private String addressCity;

    @Size(min = 2, max = 2)
    private String addressCountry;
}
