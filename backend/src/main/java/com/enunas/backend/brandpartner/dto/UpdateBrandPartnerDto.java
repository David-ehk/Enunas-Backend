package com.enunas.backend.brandpartner.dto;

import lombok.Data;

@Data
public class UpdateBrandPartnerDto {

    private String description;

    private String logoUrl;

    private String websiteUrl;

    private String instagramHandle;
}
