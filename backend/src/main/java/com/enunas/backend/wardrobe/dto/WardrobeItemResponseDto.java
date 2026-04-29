package com.enunas.backend.wardrobe.dto;

import com.enunas.backend.wardrobe.WardrobeItem;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class WardrobeItemResponseDto {

    private Long id;
    private String imageUrl;
    private String category;
    private String color;
    private String brand;
    private String styleTag;
    private boolean isPublic;
    private LocalDateTime createdAt;

    public static WardrobeItemResponseDto from(WardrobeItem item) {
        return WardrobeItemResponseDto.builder()
                .id(item.getId())
                .imageUrl(item.getImageUrl())
                .category(item.getCategory())
                .color(item.getColor())
                .brand(item.getBrand())
                .styleTag(item.getStyleTag())
                .isPublic(item.isPublic())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
