package com.enunas.backend.media;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.dto.*;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MediaService {

    private final ProductImageRepository imageRepository;
    private final ProductVideoRepository videoRepository;
    private final ProductRepository productRepository;
    private final MediaStorageService mediaStorageService;
    private final MediaUrlResolver mediaUrlResolver;

    public PresignUploadResponseDto presignUpload(Long productId, PresignUploadRequestDto dto, User owner) {
        findProductAndVerifyOwnership(productId, owner);
        if (dto.getPurpose().scope() != MediaPurpose.Scope.PRODUCT) {
            throw new IllegalArgumentException(
                    "purpose " + dto.getPurpose() + " is not valid for product media upload");
        }
        MediaStorageService.PresignedUpload upload = mediaStorageService.presignUpload(
                dto.getPurpose(), productId, dto.getContentType(), dto.getContentLength());
        return PresignUploadResponseDto.from(upload);
    }

    @Transactional
    public ProductImageResponseDto addImage(Long productId, ProductImageDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);
        mediaStorageService.verifyUploaded(dto.getStorageKey(), MediaPurpose.PRODUCT_IMAGE, productId);

        if (dto.isPrimary()) {
            imageRepository.findByProductIdAndPrimary(productId, true)
                    .ifPresent(img -> {
                        img.setPrimary(false);
                        imageRepository.save(img);
                    });
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .storageKey(dto.getStorageKey())
                .altText(dto.getAltText())
                .primary(dto.isPrimary())
                .displayOrder(dto.getDisplayOrder())
                .build();

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    public List<ProductImageResponseDto> getImages(Long productId) {
        return imageRepository.findByProductIdOrderByDisplayOrderAsc(productId).stream()
                .map(image -> ProductImageResponseDto.from(image, mediaUrlResolver))
                .toList();
    }

    @Transactional
    public void deleteImage(Long imageId, User owner) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ProductNotFoundException("Image not found with id: " + imageId));
        verifyProductOwnership(image.getProduct(), owner);
        mediaStorageService.delete(image.getStorageKey());
        imageRepository.delete(image);
    }

    @Transactional
    public ProductVideoResponseDto addVideo(Long productId, ProductVideoDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);
        mediaStorageService.verifyUploaded(dto.getStorageKey(), MediaPurpose.PRODUCT_VIDEO, productId);
        if (dto.getThumbnailStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getThumbnailStorageKey(), MediaPurpose.VIDEO_THUMB, productId);
        }

        ProductVideo video = ProductVideo.builder()
                .product(product)
                .storageKey(dto.getStorageKey())
                .title(dto.getTitle())
                .thumbnailStorageKey(dto.getThumbnailStorageKey())
                .build();

        return ProductVideoResponseDto.from(videoRepository.save(video), mediaUrlResolver);
    }

    public List<ProductVideoResponseDto> getVideos(Long productId) {
        return videoRepository.findByProductId(productId).stream()
                .map(video -> ProductVideoResponseDto.from(video, mediaUrlResolver))
                .toList();
    }

    @Transactional
    public void deleteVideo(Long videoId, User owner) {
        ProductVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ProductNotFoundException("Video not found with id: " + videoId));
        verifyProductOwnership(video.getProduct(), owner);
        mediaStorageService.delete(video.getStorageKey());
        if (video.getThumbnailStorageKey() != null) {
            mediaStorageService.delete(video.getThumbnailStorageKey());
        }
        videoRepository.delete(video);
    }

    private Product findProductAndVerifyOwnership(Long productId, User owner) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        verifyProductOwnership(product, owner);
        return product;
    }

    private void verifyProductOwnership(Product product, User creator) {
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
    }
}
