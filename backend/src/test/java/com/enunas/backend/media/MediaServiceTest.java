package com.enunas.backend.media;

import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.ProductImageDto;
import com.enunas.backend.media.dto.ProductVideoDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private ProductImageRepository imageRepository;
    @Mock private ProductVideoRepository videoRepository;
    @Mock private ProductRepository productRepository;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks private MediaService mediaService;

    private User owner;
    private Product product;

    @BeforeEach
    void setUp() {
        owner = User.builder().id(1L).build();
        product = Product.builder().id(42L).creator(owner).build();
    }

    @Test
    void addImage_confirmsUploadBeforeSaving() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");

        mediaService.addImage(42L, dto, owner);

        verify(mediaStorageService).verifyUploaded("products/42/images/abc.jpg", MediaPurpose.PRODUCT_IMAGE, 42L);
    }

    @Test
    void addImage_wrongOwner_throwsSecurityException_beforeTouchingStorage() {
        Product othersProduct = Product.builder().id(42L)
                .creator(User.builder().id(999L).build()).build();
        when(productRepository.findById(42L)).thenReturn(Optional.of(othersProduct));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");

        assertThatThrownBy(() -> mediaService.addImage(42L, dto, owner))
                .isInstanceOf(SecurityException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void addVideo_withThumbnail_confirmsBothKeys() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(videoRepository.save(any(ProductVideo.class))).thenAnswer(inv -> inv.getArgument(0));

        ProductVideoDto dto = new ProductVideoDto();
        dto.setStorageKey("products/42/videos/abc.mp4");
        dto.setThumbnailStorageKey("products/42/videos/abc-t.jpg");

        mediaService.addVideo(42L, dto, owner);

        verify(mediaStorageService).verifyUploaded("products/42/videos/abc.mp4", MediaPurpose.PRODUCT_VIDEO, 42L);
        verify(mediaStorageService).verifyUploaded("products/42/videos/abc-t.jpg", MediaPurpose.VIDEO_THUMB, 42L);
    }

    @Test
    void deleteImage_deletesFromStorageAndRepository() {
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/abc.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        mediaService.deleteImage(5L, owner);

        verify(mediaStorageService).delete("products/42/images/abc.jpg");
        verify(imageRepository).delete(image);
    }

    @Test
    void presignUpload_wrongScopePurpose_throwsIllegalArgument() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.BRAND_LOGO);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThatThrownBy(() -> mediaService.presignUpload(42L, dto, owner))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void presignUpload_delegatesToMediaStorageServiceWithProductIdAsResourceId() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(mediaStorageService.presignUpload(MediaPurpose.PRODUCT_IMAGE, 42L, "image/jpeg", 1024L))
                .thenReturn(new MediaStorageService.PresignedUpload(
                        "products/42/images/x.jpg", "https://signed.example/x", java.time.Instant.now(),
                        Map.of("Content-Type", "image/jpeg")));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.PRODUCT_IMAGE);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThat(mediaService.presignUpload(42L, dto, owner).getKey()).isEqualTo("products/42/images/x.jpg");
    }

    @Test
    void addImage_verifyUploadedFails_neverTouchesRepository() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        doThrow(new IllegalArgumentException("bad key"))
                .when(mediaStorageService).verifyUploaded(anyString(), any(MediaPurpose.class), anyLong());

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");

        assertThatThrownBy(() -> mediaService.addImage(42L, dto, owner))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(imageRepository);
    }
}
