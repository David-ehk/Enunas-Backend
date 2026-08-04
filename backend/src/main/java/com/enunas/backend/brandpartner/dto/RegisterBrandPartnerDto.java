package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

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
    @NoHtml
    private String brandName;

    /** Contact person behind the brand — first name. */
    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String firstName;

    /** Contact person behind the brand — last name. */
    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String lastName;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 255)
    @URL
    private String logoUrl;

    @Size(max = 255)
    @URL
    private String websiteUrl;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String instagramHandle;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
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
     * never validated for format/correctness here (manual check). See BrandPartnerService. Only
     * length is capped, to keep abuse/oversized input out without touching that business rule.
     */
    @Size(max = 32)
    private String vatId;

    /** Steuernummer (German tax number). Optional. Length-capped only, same rationale as vatId. */
    @Size(max = 32)
    private String taxNumber;

    // ===== §22f supplier legal name + business address — mandatory master data (no VAT logic). =====

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

    /** ISO 3166-1 alpha-2 country code of the business address. */
    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;

    // ===== Returns destination — OPTIONAL. Omit the whole block and returns fall back to the
    // §22f address above. Set it when the brand ships/receives through a 3PL or a separate
    // warehouse. Purely logistics: never feeds the `domestic` VAT flag. =====

    @Size(max = 255)
    @NoHtml
    private String returnRecipient;

    @Size(max = 255)
    @NoHtml
    private String returnStreet;

    @Size(max = 16)
    @NoHtml
    private String returnPostalCode;

    @Size(max = 128)
    @NoHtml
    private String returnCity;

    /** ISO 3166-1 alpha-2 country code of the returns destination. */
    @Size(min = 2, max = 2)
    private String returnCountry;

    @Size(max = 2000)
    @NoHtml
    private String returnInstructions;
}
