package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
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
    @NoHtml
    private String legalName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String addressStreet;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String addressPostalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String addressCity;

    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;

    @Size(max = 32)
    private String vatId;

    @Size(max = 32)
    private String taxNumber;
}
