package com.enunas.backend.brandpartner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin update of a brand's §22f master data. Constraints mirror the onboarding DTO
 * ({@code RegisterBrandPartnerDto}) so the admin and vendor paths validate identically — address
 * fields mandatory, country 2-letter. vatId / taxNumber stay optional (no format validation).
 * Scope is strictly these fields: no financial, payout, or {@code domestic} data.
 */
@Data
public class AdminBrandMasterDataDto {

    @NotBlank
    @Size(max = 255)
    private String legalName;

    @NotBlank
    @Size(max = 255)
    private String addressStreet;

    @NotBlank
    @Size(max = 16)
    private String addressPostalCode;

    @NotBlank
    @Size(max = 128)
    private String addressCity;

    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;

    private String vatId;

    private String taxNumber;
}
