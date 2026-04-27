package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductImage;
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
    private LocalDateTime createdAt;

    public static ProductImageResponseDto from(ProductImage image) {
        return ProductImageResponseDto.builder()
                .id(image.getId())
                .imageUrl(image.getImageUrl())
                .altText(image.getAltText())
                .primary(image.isPrimary())
                .displayOrder(image.getDisplayOrder())
                .createdAt(image.getCreatedAt())
                .build();
    }
}
