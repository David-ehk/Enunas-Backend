package com.enunas.backend.media;

import com.enunas.backend.media.dto.ProductImageResponseDto;
import com.enunas.backend.media.dto.ProductVideoResponseDto;
import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaUrlResolver;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMediaDtoTest {

    private MediaUrlResolver resolver() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.it.local");
        return new MediaUrlResolver(properties);
    }

    @Test
    void productImageResponseDto_resolvesImageUrlFromStorageKey() {
        ProductImage image = ProductImage.builder()
                .id(1L).storageKey("products/1/images/abc.jpg").altText("front")
                .primary(true).displayOrder(0).createdAt(LocalDateTime.now()).build();

        ProductImageResponseDto dto = ProductImageResponseDto.from(image, resolver());

        assertThat(dto.getImageUrl()).isEqualTo("https://cdn.it.local/products/1/images/abc.jpg");
        assertThat(dto.getAltText()).isEqualTo("front");
    }

    @Test
    void productVideoResponseDto_resolvesVideoUrlAndThumbnailUrl() {
        ProductVideo video = ProductVideo.builder()
                .id(1L).storageKey("products/1/videos/abc.mp4")
                .thumbnailStorageKey("products/1/videos/abc-t.jpg")
                .title("Demo").createdAt(LocalDateTime.now()).build();

        ProductVideoResponseDto dto = ProductVideoResponseDto.from(video, resolver());

        assertThat(dto.getVideoUrl()).isEqualTo("https://cdn.it.local/products/1/videos/abc.mp4");
        assertThat(dto.getThumbnailUrl()).isEqualTo("https://cdn.it.local/products/1/videos/abc-t.jpg");
    }

    @Test
    void productVideoResponseDto_noThumbnail_resolvesToNull() {
        ProductVideo video = ProductVideo.builder()
                .id(1L).storageKey("products/1/videos/abc.mp4").createdAt(LocalDateTime.now()).build();

        assertThat(ProductVideoResponseDto.from(video, resolver()).getThumbnailUrl()).isNull();
    }
}
