package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class UpdateBrandPartnerDto {

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 512)
    private String logoStorageKey;

    @Size(max = 512)
    private String heroStorageKey;

    @Size(max = 255)
    @URL
    private String websiteUrl;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String instagramHandle;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String tiktokHandle;

    @Size(min = 2, max = 2)
    private String country;

    @Email
    private String contactEmail;

    @Size(max = 32)
    private String vatId;

    @Size(max = 32)
    private String taxNumber;

    // §22f supplier legal name + address — optional on update (null = keep current).
    @Size(max = 255)
    @NoHtml
    private String legalName;

    @Size(max = 255)
    @NoHtml
    private String addressStreet;

    @Size(max = 16)
    @NoHtml
    private String addressPostalCode;

    @Size(max = 128)
    @NoHtml
    private String addressCity;

    @Size(min = 2, max = 2)
    private String addressCountry;

    // Returns destination — optional on update (null = keep current). Brands self-serve this via
    // PATCH /brandpartner/me: moving warehouses must not require an admin. Returns already in
    // flight are unaffected, because they snapshot the address at request time.
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

    @Size(min = 2, max = 2)
    private String returnCountry;

    @Size(max = 2000)
    @NoHtml
    private String returnInstructions;
}
