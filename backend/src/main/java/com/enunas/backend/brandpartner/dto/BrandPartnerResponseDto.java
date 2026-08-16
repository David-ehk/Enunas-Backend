package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandReturnAddress;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BrandPartnerResponseDto {

    private Long id;
    private String brandName;
    private String slug;
    /** Contact person behind the brand — null for brands onboarded before V12. */
    private String firstName;
    private String lastName;
    private String description;
    private String logoUrl;
    /** Net-new, key-only from birth — no legacy free-text hero URL ever existed. */
    private String heroImageUrl;
    private String websiteUrl;
    private String instagramHandle;
    private String tiktokHandle;
    private String country;
    private String contactEmail;
    private String vatId;
    private String taxNumber;
    /** Derived from addressCountry (DE ⇒ true). Drives the Inland/Ausland badge + reverse charge. */
    private boolean domestic;
    private String legalName;
    private String addressStreet;
    private String addressPostalCode;
    private String addressCity;
    private String addressCountry;
    /** Nominated returns destination — null when the brand falls back to its §22f address. */
    private String returnRecipient;
    private String returnStreet;
    private String returnPostalCode;
    private String returnCity;
    private String returnCountry;
    private String returnInstructions;
    /**
     * The address returns are actually routed to, with the fallback already applied — what the
     * brand dashboard should display, so a brand can see the consequence of leaving it unset.
     */
    private String effectiveReturnAddress;
    private BrandStatus status;
    private boolean approved;
    private Long userId;
    private String userEmail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BrandPartnerResponseDto from(BrandPartner brand, MediaUrlResolver mediaUrlResolver) {
        return BrandPartnerResponseDto.builder()
                .id(brand.getId())
                .brandName(brand.getBrandName())
                .slug(brand.getSlug())
                .firstName(brand.getFirstName())
                .lastName(brand.getLastName())
                .description(brand.getDescription())
                .logoUrl(mediaUrlResolver.resolve(brand.getLogoStorageKey()))
                .heroImageUrl(mediaUrlResolver.resolve(brand.getHeroStorageKey()))
                .websiteUrl(brand.getWebsiteUrl())
                .instagramHandle(brand.getInstagramHandle())
                .tiktokHandle(brand.getTiktokHandle())
                .country(brand.getCountry())
                .contactEmail(brand.getContactEmail())
                .vatId(brand.getVatId())
                .taxNumber(brand.getTaxNumber())
                .domestic(brand.isDomestic())
                .legalName(brand.getLegalName())
                .addressStreet(brand.getAddressStreet())
                .addressPostalCode(brand.getAddressPostalCode())
                .addressCity(brand.getAddressCity())
                .addressCountry(brand.getAddressCountry())
                .returnRecipient(brand.getReturnRecipient())
                .returnStreet(brand.getReturnStreet())
                .returnPostalCode(brand.getReturnPostalCode())
                .returnCity(brand.getReturnCity())
                .returnCountry(brand.getReturnCountry())
                .returnInstructions(brand.getReturnInstructions())
                .effectiveReturnAddress(BrandReturnAddress.of(brand).formatted())
                .status(brand.getStatus())
                .approved(brand.isApproved())
                .userId(brand.getUser() != null ? brand.getUser().getId() : null)
                .userEmail(brand.getUser() != null ? brand.getUser().getEmail() : null)
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
