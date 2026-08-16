package com.enunas.backend.media.dto;

import com.enunas.backend.media.ProductVideo;
import com.enunas.backend.media.storage.MediaUrlResolver;
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

    public static ProductVideoResponseDto from(ProductVideo video, MediaUrlResolver resolver) {
        return ProductVideoResponseDto.builder()
                .id(video.getId())
                .videoUrl(resolver.resolve(video.getStorageKey()))
                .title(video.getTitle())
                .thumbnailUrl(resolver.resolve(video.getThumbnailStorageKey()))
                .createdAt(video.getCreatedAt())
                .build();
    }
}
