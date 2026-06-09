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

    private String tiktokHandle;

    /** ISO 3166-1 alpha-2 country code (e.g. "DE", "US"). Optional at apply time. */
    @Size(min = 2, max = 2)
    private String country;

    /** Public business contact email. Optional; defaults to login email if omitted. */
    @Email
    private String contactEmail;

    /**
     * USt-IdNr (VAT identification number). Captured at onboarding. Whether it is mandatory is
     * controlled application-side by the {@code enunas.brand.vat-id-required} toggle (default false) —
     * never validated for format/correctness here (manual check). See BrandPartnerService.
     */
    private String vatId;

    /** Steuernummer (German tax number). Optional. */
    private String taxNumber;

    // ===== §22f supplier legal name + business address — mandatory master data (no VAT logic). =====

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

    /** ISO 3166-1 alpha-2 country code of the business address. */
    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;
}
