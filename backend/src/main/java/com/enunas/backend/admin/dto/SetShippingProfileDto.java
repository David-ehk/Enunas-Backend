package com.enunas.backend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Request body for {@code PATCH /admin/brands/{id}/shipping-profile}.
 *
 * <p><b>FULL-REPLACE PAYLOAD — every field below is written on every request.</b> This DTO has no
 * "was this field present in the JSON?" tracking: an omitted field and an explicit {@code null}
 * both arrive here as {@code null}, and
 * {@link com.enunas.backend.admin.AdminService#setBrandShippingProfile} writes that {@code null}
 * straight through. <b>Omitting a field therefore CLEARS it</b> — sending
 * {@code {"avgShippingDays": 3}} against a brand configured at €9.99 silently drops that brand to
 * the platform {@code GLOBAL_DEFAULT} shipping rate.
 *
 * <p><b>Clients must submit all three fields on every call</b>, echoing back any value they do not
 * intend to change. Treat this as "save the whole shipping config", not a field-level patch. See
 * {@link com.enunas.backend.admin.AdminService#setBrandShippingProfile}'s javadoc for the full
 * rationale (it is what keeps "clear shippingCost back to unset" expressible at all).
 */
@Getter
public class SetShippingProfileDto {

    /** Flat shipping cost for this brand, in EUR. {@code null} — whether sent explicitly OR simply
     * omitted from the request — clears it back to "not configured" (falls back to the platform
     * default rate). {@code 0.00} explicitly marks the brand as free-shipping — see
     * {@code BrandShippingProfile#shippingCost}'s javadoc for why these are two different,
     * deliberately distinct outcomes. */
    @DecimalMin(value = "0.00", message = "must not be negative")
    private BigDecimal shippingCost;

    /** ISO-3166 alpha-2 origin country. Omitting it clears the stored value — see class javadoc. */
    @Size(min = 2, max = 2)
    private String originCountry;

    /** Average handling/shipping days. Omitting it clears the stored value — see class javadoc. */
    private Integer avgShippingDays;
}
