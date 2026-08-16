package com.enunas.backend.admin.dto;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.*;
import com.enunas.backend.product.dto.CompleteTheLookCardDto;
import com.enunas.backend.product.dto.ProductVariantResponseDto;
import com.enunas.backend.user.User;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Product view that includes moderation metadata. Returned by admin endpoints only —
 * never leak this DTO to public/customer routes.
 */
@Getter
@Builder
public class AdminProductResponseDto {

    private Long id;
    private String name;
    private Long brandId;
    private String brandName;
    private String description;
    private String inspirationStory;
    private ProductCategory category;
    private List<ProductCatalogueCategory> catalogueCategory;
    private ProductType productType;
    private OutfitSlot outfitSlot;
    private Gender gender;
    private String material;
    private String originCountry;
    private String careInstructions;
    private String collectionName;
    private LocalDate releaseDate;
    private int returnPeriodDays;
    private ProductStatus status;
    private Long creatorId;
    private String creatorEmail;
    private Boolean completeTheLookEnabled;
    private List<CompleteTheLookCardDto> completeTheLookProducts;
    private List<ProductVariantResponseDto> variants;
    private List<ProductImageResponseDto> images;
    private List<ProductVideoResponseDto> videos;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ===== Moderation metadata (admin-only) =====
    private Long moderatedById;
    private String moderatedByEmail;
    private LocalDateTime moderatedAt;
    private String rejectionReason;

    public static AdminProductResponseDto from(Product product, MediaUrlResolver resolver) {
        User moderator = product.getModeratedBy();
        return AdminProductResponseDto.builder()
                .id(product.getId())
                .name(product.getName())
                .brandId(product.getBrand() != null ? product.getBrand().getId() : null)
                .brandName(product.getBrand() != null ? product.getBrand().getBrandName() : null)
                .description(product.getDescription())
                .inspirationStory(product.getInspirationStory())
                .category(product.getCategory())
                .catalogueCategory(product.getCatalogueCategory())
                .productType(product.getProductType())
                .outfitSlot(product.getOutfitSlot())
                .gender(product.getGender())
                .material(product.getMaterial())
                .originCountry(product.getOriginCountry())
                .careInstructions(product.getCareInstructions())
                .collectionName(product.getCollectionName())
                .releaseDate(product.getReleaseDate())
                .returnPeriodDays(product.getReturnPeriodDays())
                .status(product.getStatus())
                .creatorId(product.getCreator().getId())
                .creatorEmail(product.getCreator().getEmail())
                .completeTheLookEnabled(product.getCompleteTheLookEnabled())
                .completeTheLookProducts(product.getCompleteTheLookProducts().stream()
                        .map(p -> CompleteTheLookCardDto.from(p, resolver))
                        .toList())
                .variants(product.getVariants().stream()
                        .map(ProductVariantResponseDto::from)
                        .toList())
                .images(product.getImages().stream()
                        .map(img -> ProductImageResponseDto.from(img, resolver))
                        .toList())
                .videos(product.getVideos().stream()
                        .map(video -> ProductVideoResponseDto.from(video, resolver))
                        .toList())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .moderatedById(moderator != null ? moderator.getId() : null)
                .moderatedByEmail(moderator != null ? moderator.getEmail() : null)
                .moderatedAt(product.getModeratedAt())
                .rejectionReason(product.getRejectionReason())
                .build();
    }
}
