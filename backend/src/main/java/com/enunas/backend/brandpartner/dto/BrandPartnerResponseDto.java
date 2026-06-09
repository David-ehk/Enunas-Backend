package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BrandPartnerResponseDto {

    private Long id;
    private String brandName;
    private String slug;
    private String description;
    private String logoUrl;
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
    private BrandStatus status;
    private boolean approved;
    private Long userId;
    private String userEmail;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static BrandPartnerResponseDto from(BrandPartner brand) {
        return BrandPartnerResponseDto.builder()
                .id(brand.getId())
                .brandName(brand.getBrandName())
                .slug(brand.getSlug())
                .description(brand.getDescription())
                .logoUrl(brand.getLogoUrl())
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
                .status(brand.getStatus())
                .approved(brand.isApproved())
                .userId(brand.getUser() != null ? brand.getUser().getId() : null)
                .userEmail(brand.getUser() != null ? brand.getUser().getEmail() : null)
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
