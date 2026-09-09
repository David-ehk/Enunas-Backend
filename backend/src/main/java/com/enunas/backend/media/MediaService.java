package com.enunas.backend.media;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.dto.*;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MediaService {

    private final ProductImageRepository imageRepository;
    private final ProductVideoRepository videoRepository;
    private final ProductRepository productRepository;
    private final ProductColorRepository productColorRepository;
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

        ProductColor colour = resolveColourForProduct(dto.getProductColorId(), productId);

        if (dto.isPrimary()) {
            // The V32 partial unique indexes over is_primary are enforced per statement — the old
            // primary's demotion must reach the DB before the INSERT of this new is_primary=true row
            // (IDENTITY forces an immediate INSERT), or they collide in the same colour group.
            imageRepository.findByProductIdAndProductColorIdAndPrimary(productId, dto.getProductColorId(), true)
                    .ifPresent(img -> {
                        img.setPrimary(false);
                        imageRepository.saveAndFlush(img);
                    });
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .productColor(colour)
                .storageKey(dto.getStorageKey())
                .altText(dto.getAltText())
                .primary(dto.isPrimary())
                .displayOrder(dto.getDisplayOrder())
                .build();

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    /**
     * Edit image metadata without re-uploading bytes. Partial update — an omitted field is
     * unchanged. Colour: {@code productColorId} assigns, {@code unassignColor=true} moves to the
     * shared group. Setting a colour that differs from the current one drops the image's primary
     * flag unless {@code primary=true} is also sent, so it can never collide with the target
     * group's existing primary.
     */
    @Transactional
    public ProductImageResponseDto updateImage(Long productId, Long imageId,
                                               UpdateProductImageDto dto, User owner) {
        findProductAndVerifyOwnership(productId, owner);
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ProductNotFoundException("Image not found with id: " + imageId));
        if (!image.getProduct().getId().equals(productId)) {
            throw new SecurityException("Image does not belong to this product");
        }

        Long currentColourId = image.getProductColor() != null ? image.getProductColor().getId() : null;

        // Resolve the post-update colour BEFORE mutating `image`. The V32 partial unique indexes on
        // is_primary are enforced per statement, so a half-applied row auto-flushed by the derived
        // query inside demoteCurrentPrimary (or by commit-time update ordering) would collide with
        // the target group's existing primary. Order is: resolve → demote-and-flush → mutate.
        ProductColor targetColour;
        boolean colourTouched;
        if (Boolean.TRUE.equals(dto.getUnassignColor())) {
            targetColour = null;
            colourTouched = true;
        } else if (dto.getProductColorId() != null) {
            targetColour = resolveColourForProduct(dto.getProductColorId(), productId);
            colourTouched = true;
        } else {
            targetColour = image.getProductColor();
            colourTouched = false;
        }
        Long targetColourId = targetColour != null ? targetColour.getId() : null;
        boolean colourChanged = colourTouched && !Objects.equals(currentColourId, targetColourId);

        if (Boolean.TRUE.equals(dto.getPrimary())) {
            demoteCurrentPrimary(productId, targetColourId, imageId);
        }

        if (colourTouched) image.setProductColor(targetColour);
        if (dto.getAltText() != null) image.setAltText(dto.getAltText());
        if (dto.getDisplayOrder() != null) image.setDisplayOrder(dto.getDisplayOrder());

        if (Boolean.TRUE.equals(dto.getPrimary())) {
            image.setPrimary(true);
        } else if (Boolean.FALSE.equals(dto.getPrimary()) || (colourChanged && image.isPrimary())) {
            image.setPrimary(false);
        }

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    private void demoteCurrentPrimary(Long productId, Long colourId, Long exceptImageId) {
        imageRepository.findByProductIdAndProductColorIdAndPrimary(productId, colourId, true)
                .filter(existing -> !existing.getId().equals(exceptImageId))
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    // Flush now: the demotion must land in the DB before the caller writes a second
                    // is_primary=true into this group (per-statement partial unique index).
                    imageRepository.saveAndFlush(existing);
                });
    }

    @Transactional(readOnly = true)
    public List<ProductImageResponseDto> getImages(Long productId, Long colorId) {
        return imageRepository.findForProductAndOptionalColour(productId, colorId).stream()
                .sorted(Comparator
                        .comparing((ProductImage i) -> !i.isPrimary())
                        // Within the primary block only: a colour-filtered result carries the
                        // colour's own cover AND the shared cover — put the colour's own first so
                        // images[0] is correct without the client disambiguating.
                        .thenComparing(MediaService::sharedPrimaryLast)
                        .thenComparingInt(ProductImage::getDisplayOrder)
                        .thenComparing(ProductImage::getId))
                .map(image -> ProductImageResponseDto.from(image, mediaUrlResolver))
                .toList();
    }

    /** Ranks a shared (untagged) primary after a colour-tagged primary; 0 for every non-primary
     *  row so this never reorders anything outside the primary block. */
    private static int sharedPrimaryLast(ProductImage i) {
        return i.isPrimary() && i.getProductColor() == null ? 1 : 0;
    }

    /**
     * Loads the colourway a media mutation targets and asserts it belongs to {@code productId}.
     * Null id → null (the shared group). A colour id from another product is a cross-tenant
     * attempt — same 403 as a foreign storageKey.
     */
    private ProductColor resolveColourForProduct(Long productColorId, Long productId) {
        if (productColorId == null) {
            return null;
        }
        ProductColor colour = productColorRepository.findById(productColorId)
                .orElseThrow(() -> new ProductNotFoundException("Colour not found with id: " + productColorId));
        if (!colour.getProduct().getId().equals(productId)) {
            throw new SecurityException("Colour does not belong to this product");
        }
        return colour;
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
