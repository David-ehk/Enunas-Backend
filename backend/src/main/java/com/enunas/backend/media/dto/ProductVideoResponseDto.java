package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductVideo;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ProductVideoResponseDto {

    private Long id;
    private String videoUrl;
    private String title;
    private String thumbnailUrl;
    private LocalDateTime createdAt;

    public static ProductVideoResponseDto from(ProductVideo video) {
        return ProductVideoResponseDto.builder()
                .id(video.getId())
                .videoUrl(video.getVideoUrl())
                .title(video.getTitle())
                .thumbnailUrl(video.getThumbnailUrl())
                .createdAt(video.getCreatedAt())
                .build();
    }
}
