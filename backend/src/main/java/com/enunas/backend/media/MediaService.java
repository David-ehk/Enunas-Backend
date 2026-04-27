package com.enunas.backend.media;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.dto.*;
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

    @Transactional
    public ProductImageResponseDto addImage(Long productId, ProductImageDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);

        if (dto.isPrimary()) {
            imageRepository.findByProductIdAndPrimary(productId, true)
                    .ifPresent(img -> {
                        img.setPrimary(false);
                        imageRepository.save(img);
                    });
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .imageUrl(dto.getImageUrl())
                .altText(dto.getAltText())
                .primary(dto.isPrimary())
                .displayOrder(dto.getDisplayOrder())
                .build();

        return ProductImageResponseDto.from(imageRepository.save(image));
    }

    public List<ProductImageResponseDto> getImages(Long productId) {
        return imageRepository.findByProductIdOrderByDisplayOrderAsc(productId).stream()
                .map(ProductImageResponseDto::from)
                .toList();
    }

    @Transactional
    public void deleteImage(Long imageId, User owner) {
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ProductNotFoundException("Image not found with id: " + imageId));
        verifyProductOwnership(image.getProduct(), owner);
        imageRepository.delete(image);
    }

    @Transactional
    public ProductVideoResponseDto addVideo(Long productId, ProductVideoDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);

        ProductVideo video = ProductVideo.builder()
                .product(product)
                .videoUrl(dto.getVideoUrl())
                .title(dto.getTitle())
                .thumbnailUrl(dto.getThumbnailUrl())
                .build();

        return ProductVideoResponseDto.from(videoRepository.save(video));
    }

    public List<ProductVideoResponseDto> getVideos(Long productId) {
        return videoRepository.findByProductId(productId).stream()
                .map(ProductVideoResponseDto::from)
                .toList();
    }

    @Transactional
    public void deleteVideo(Long videoId, User owner) {
        ProductVideo video = videoRepository.findById(videoId)
                .orElseThrow(() -> new ProductNotFoundException("Video not found with id: " + videoId));
        verifyProductOwnership(video.getProduct(), owner);
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
