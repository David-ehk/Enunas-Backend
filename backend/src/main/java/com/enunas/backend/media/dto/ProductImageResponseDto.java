package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.storage.MediaUrlResolver;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ProductImageResponseDto {

    private Long id;
    private String imageUrl;
    private String altText;
    private boolean primary;
    private int displayOrder;
    private Long productColorId;
    private String color;
    private LocalDateTime createdAt;

    public static ProductImageResponseDto from(ProductImage image, MediaUrlResolver resolver) {
        return ProductImageResponseDto.builder()
                .id(image.getId())
                .imageUrl(resolver.resolve(image.getStorageKey()))
                .altText(image.getAltText())
                .primary(image.isPrimary())
                .displayOrder(image.getDisplayOrder())
                .productColorId(image.getProductColor() != null ? image.getProductColor().getId() : null)
                .color(image.getProductColor() != null ? image.getProductColor().getColor() : null)
                .createdAt(image.getCreatedAt())
                .build();
    }
}
