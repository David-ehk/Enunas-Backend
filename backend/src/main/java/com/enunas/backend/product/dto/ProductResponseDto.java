package com.enunas.backend.product.dto;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.product.*;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;

@Getter
@Builder
public class ProductResponseDto {

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

    /** Maps product to DTO without CTL prices (price = null). */
    public static ProductResponseDto from(Product product) {
        return from(product, id -> null);
    }

    /**
     * Maps product to DTO with CTL card prices supplied by {@code ctlPriceProvider}.
     * The provider receives the related product's id and returns its lowest active price,
     * or null when no active listing exists.
     */
    public static ProductResponseDto from(Product product, Function<Long, BigDecimal> ctlPriceProvider) {
        return ProductResponseDto.builder()
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
                        .map(p -> CompleteTheLookCardDto.from(p, ctlPriceProvider.apply(p.getId())))
                        .toList())
                .variants(product.getVariants().stream()
                        .map(ProductVariantResponseDto::from)
                        .toList())
                .images(product.getImages().stream()
                        .map(ProductImageResponseDto::from)
                        .toList())
                .videos(product.getVideos().stream()
                        .map(ProductVideoResponseDto::from)
                        .toList())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }
}
