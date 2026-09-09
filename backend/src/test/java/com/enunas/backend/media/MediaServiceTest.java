package com.enunas.backend.media;

import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.ProductImageDto;
import com.enunas.backend.media.dto.ProductVideoDto;
import com.enunas.backend.media.dto.UpdateProductImageDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock private ProductImageRepository imageRepository;
    @Mock private ProductVideoRepository videoRepository;
    @Mock private ProductRepository productRepository;
    @Mock private ProductColorRepository productColorRepository;
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

    @Test
    void addImage_withColourOfAnotherProduct_throwsSecurityException() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        Product otherProduct = Product.builder().id(99L).build();
        ProductColor foreignColour = ProductColor.builder().id(7L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(otherProduct).build();
        when(productColorRepository.findById(7L)).thenReturn(Optional.of(foreignColour));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");
        dto.setProductColorId(7L);

        assertThatThrownBy(() -> mediaService.addImage(42L, dto, owner))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void addImage_primary_unsetsOnlyWithinSameColourGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        when(productColorRepository.findById(3L)).thenReturn(Optional.of(black));
        ProductImage oldPrimary = ProductImage.builder().id(50L).product(product)
                .productColor(black).storageKey("products/42/images/old.jpg").primary(true).build();
        ProductImage whitePrimary = ProductImage.builder().id(51L).product(product)
                .storageKey("products/42/images/white.jpg").primary(true).build();
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, 3L, true))
                .thenReturn(Optional.of(oldPrimary));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/new.jpg");
        dto.setProductColorId(3L);
        dto.setPrimary(true);

        mediaService.addImage(42L, dto, owner);

        // positive: the BLACK group's primary is demoted, and flushed before the new INSERT
        assertThat(oldPrimary.isPrimary()).isFalse();
        verify(imageRepository).saveAndFlush(oldPrimary);
        // negative: no other group is even looked up, so a primary elsewhere is untouched
        assertThat(whitePrimary.isPrimary()).isTrue();
        verify(imageRepository, never()).save(whitePrimary);
        verify(imageRepository, never()).saveAndFlush(whitePrimary);
        verify(imageRepository, never())
                .findByProductIdAndProductColorIdAndPrimary(eq(42L), isNull(), anyBoolean());
    }

    @Test
    void addImage_sharedPrimary_looksUpNullColourGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, null, true))
                .thenReturn(Optional.empty());

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/new.jpg");
        dto.setPrimary(true);

        mediaService.addImage(42L, dto, owner);

        verify(imageRepository).findByProductIdAndProductColorIdAndPrimary(42L, null, true);
    }

    @Test
    void updateImage_reassignsColour() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor white = ProductColor.builder().id(4L).color("White")
                .colorFamily(ColorFamily.WHITE).product(product).build();
        when(productColorRepository.findById(4L)).thenReturn(Optional.of(white));
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/x.jpg").primary(false).build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setProductColorId(4L);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(image.getProductColor()).isEqualTo(white);
    }

    @Test
    void updateImage_unassignColour_movesToShared() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        ProductImage image = ProductImage.builder().id(5L).product(product).productColor(black)
                .storageKey("products/42/images/x.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setUnassignColor(true);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(image.getProductColor()).isNull();
        verifyNoInteractions(productColorRepository);
    }

    @Test
    void updateImage_imageOnAnotherProduct_throwsSecurityException() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        Product other = Product.builder().id(77L).build();
        ProductImage image = ProductImage.builder().id(5L).product(other)
                .storageKey("products/77/images/x.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> mediaService.updateImage(42L, 5L, new UpdateProductImageDto(), owner))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void updateImage_missingImage_throwsNotFound() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.updateImage(42L, 5L, new UpdateProductImageDto(), owner))
                .isInstanceOf(com.enunas.backend.exception.ProductNotFoundException.class);
    }

    @Test
    void updateImage_setPrimary_demotesCurrentPrimaryInTargetGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/x.jpg").primary(false).build();
        ProductImage currentPrimary = ProductImage.builder().id(6L).product(product)
                .storageKey("products/42/images/y.jpg").primary(true).build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, null, true))
                .thenReturn(Optional.of(currentPrimary));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setPrimary(true);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(currentPrimary.isPrimary()).isFalse();
        assertThat(image.isPrimary()).isTrue();
    }

    @Test
    void updateImage_altTextOnly_leavesColourAndPrimaryUntouched() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        ProductImage image = ProductImage.builder().id(5L).product(product).productColor(black)
                .storageKey("products/42/images/x.jpg").primary(true).build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setAltText("new alt");
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(image.getProductColor()).isEqualTo(black);
        assertThat(image.isPrimary()).isTrue();
        assertThat(image.getAltText()).isEqualTo("new alt");
        verifyNoInteractions(productColorRepository);
        verify(imageRepository, never()).findByProductIdAndProductColorIdAndPrimary(anyLong(), any(), anyBoolean());
    }

    @Test
    void getImages_sortsPrimaryFirstThenDisplayOrder() {
        ProductImage a = ProductImage.builder().id(1L).product(product)
                .storageKey("products/42/images/a.jpg").primary(false).displayOrder(2).build();
        ProductImage b = ProductImage.builder().id(2L).product(product)
                .storageKey("products/42/images/b.jpg").primary(true).displayOrder(5).build();
        ProductImage c = ProductImage.builder().id(3L).product(product)
                .storageKey("products/42/images/c.jpg").primary(false).displayOrder(1).build();
        when(imageRepository.findForProductAndOptionalColour(42L, null))
                .thenReturn(java.util.List.of(a, b, c));
        when(mediaUrlResolver.resolve(anyString())).thenAnswer(inv -> "cdn/" + inv.getArgument(0));

        var result = mediaService.getImages(42L, null);

        assertThat(result).extracting(dto -> dto.getImageUrl().substring(dto.getImageUrl().lastIndexOf('/') + 1))
                .containsExactly("b.jpg", "c.jpg", "a.jpg");
    }

    @Test
    void getImages_passesColourIdThrough() {
        when(imageRepository.findForProductAndOptionalColour(42L, 9L)).thenReturn(java.util.List.of());
        mediaService.getImages(42L, 9L);
        verify(imageRepository).findForProductAndOptionalColour(42L, 9L);
    }
}
