package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

/**
 * Public, unauthenticated brand-profile read — storefront-safe subset of
 * {@link BrandPartnerResponseDto}. Deliberately excludes everything that DTO carries for the
 * brand's own dashboard/admin use: vatId, taxNumber, legalName, business + return addresses,
 * contactEmail, userEmail, approval/status. None of that belongs on a page a logged-out visitor
 * can load.
 */
@Getter
@Builder
public class BrandPublicProfileDto {

    private Long id;
    private String brandName;
    private String slug;
    private String description;
    private String logoUrl;
    private String heroImageUrl;
    private String websiteUrl;
    private String instagramHandle;
    private String tiktokHandle;
    private String country;

    public static BrandPublicProfileDto from(BrandPartner brand, MediaUrlResolver mediaUrlResolver) {
        return BrandPublicProfileDto.builder()
                .id(brand.getId())
                .brandName(brand.getBrandName())
                .slug(brand.getSlug())
                .description(brand.getDescription())
                .logoUrl(mediaUrlResolver.resolve(brand.getLogoStorageKey()))
                .heroImageUrl(mediaUrlResolver.resolve(brand.getHeroStorageKey()))
                .websiteUrl(brand.getWebsiteUrl())
                .instagramHandle(brand.getInstagramHandle())
                .tiktokHandle(brand.getTiktokHandle())
                .country(brand.getCountry())
                .build();
    }
}
