package com.enunas.backend.product.dto;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.media.storage.MediaUrlResolver;
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
    private String slug;
    /** What the customer pays: the cheapest currently-active listing's price, after any discount
     *  on that listing. Null when the product has no active listing. */
    private BigDecimal price;

    /**
     * The price {@link #price} was reduced from — the same listing's undiscounted price — for the
     * storefront to strike through. Null whenever the product is not on sale, so "on sale" is
     * exactly "originalPrice is present"; it is never equal to {@code price}.
     */
    private BigDecimal originalPrice;
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

    /** Maps product to DTO without own/CTL prices (price = null). */
    public static ProductResponseDto from(Product product, MediaUrlResolver resolver) {
        return from(product, null, id -> null, resolver);
    }

    /** Back-compat overload: CTL prices only, own price = null. */
    public static ProductResponseDto from(Product product, Function<Long, DisplayPrice> ctlPriceProvider, MediaUrlResolver resolver) {
        return from(product, null, ctlPriceProvider, resolver);
    }

    /**
     * Maps product to DTO with the product's own lowest active listing {@code price} and
     * CTL card prices supplied by {@code ctlPriceProvider}. The provider receives the related
     * product's id and returns its lowest active price, or null when no active listing exists.
     */
    public static ProductResponseDto from(Product product, DisplayPrice price, Function<Long, DisplayPrice> ctlPriceProvider, MediaUrlResolver resolver) {
        return ProductResponseDto.builder()
                .id(product.getId())
                .name(product.getName())
                .slug(product.getSlug())
                .price(price != null ? price.current() : null)
                .originalPrice(price != null ? price.original() : null)
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
                        .map(p -> CompleteTheLookCardDto.from(p, ctlPriceProvider.apply(p.getId()), resolver))
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
                .build();
    }
}
