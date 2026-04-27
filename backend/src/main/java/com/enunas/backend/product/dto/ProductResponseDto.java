package com.enunas.backend.product.dto;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.product.*;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
    private ProductCatalogueCategory catalogueCategory;
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
    private List<ProductVariantResponseDto> variants;
    private List<ProductImageResponseDto> images;
    private List<ProductVideoResponseDto> videos;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ProductResponseDto from(Product product) {
        return ProductResponseDto.builder()
                .id(product.getId())
                .name(product.getName())
                .brandId(product.getBrand() != null ? product.getBrand().getId() : null)
                .brandName(product.getBrand() != null ? product.getBrand().getBrandName() : null)
                .description(product.getDescription())
                .inspirationStory(product.getInspirationStory())
                .category(product.getCategory())
                .catalogueCategory(product.getCatalogueCategory())
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
