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
    private String description;
    private String logoUrl;
    private String websiteUrl;
    private String instagramHandle;
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
                .description(brand.getDescription())
                .logoUrl(brand.getLogoUrl())
                .websiteUrl(brand.getWebsiteUrl())
                .instagramHandle(brand.getInstagramHandle())
                .status(brand.getStatus())
                .approved(brand.isApproved())
                .userId(brand.getUser() != null ? brand.getUser().getId() : null)
                .userEmail(brand.getUser() != null ? brand.getUser().getEmail() : null)
                .createdAt(brand.getCreatedAt())
                .updatedAt(brand.getUpdatedAt())
                .build();
    }
}
