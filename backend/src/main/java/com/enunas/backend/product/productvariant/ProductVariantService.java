package com.enunas.backend.product.productvariant;

import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.dto.ProductVariantDto;
import com.enunas.backend.product.dto.ProductVariantResponseDto;
import com.enunas.backend.product.dto.UpdateProductVariantDto;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductVariantService {

    private static final String SKU_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProductVariantRepository variantRepository;
    private final ProductColorRepository productColorRepository;
    private final ProductRepository productRepository;

    @Transactional
    public ProductVariantResponseDto addVariant(Long productId, ProductVariantDto dto, User creator) {
        Product product = findProductAndVerifyOwnership(productId, creator);

        ProductColor productColor = findOrCreateColor(product, dto.getColor());

        if (variantRepository.existsByProductColorIdAndSize(productColor.getId(), dto.getSize())) {
            throw new IllegalArgumentException(
                "Variant already exists for color '" + dto.getColor() + "' and size '" + dto.getSize() + "'");
        }

        ProductVariant variant = ProductVariant.builder()
                .productColor(productColor)
                .size(dto.getSize())
                .stockQuantity(dto.getStockQuantity())
                .weightGrams(dto.getWeightGrams())
                .product(product)
                .build();

        return ProductVariantResponseDto.from(variantRepository.save(variant));
    }

    @Transactional(readOnly = true)
    public List<ProductVariantResponseDto> getVariants(Long productId) {
        return variantRepository.findByProductId(productId).stream()
                .map(ProductVariantResponseDto::from)
                .toList();
    }

    @Transactional
    public ProductVariantResponseDto updateVariant(Long productId, Long variantId, UpdateProductVariantDto dto, User creator) {
        Product product = findProductAndVerifyOwnership(productId, creator);
        ProductVariant variant = findVariant(variantId);

        String targetColor = dto.getColor() != null ? dto.getColor() : variant.getColor();
        String targetSize  = dto.getSize()  != null ? dto.getSize()  : variant.getSize();

        boolean colorChanged = dto.getColor() != null && !dto.getColor().equals(variant.getColor());
        boolean sizeChanged  = dto.getSize()  != null && !dto.getSize().equals(variant.getSize());

        if (colorChanged || sizeChanged) {
            ProductColor targetColor_ = colorChanged
                    ? findOrCreateColor(product, dto.getColor())
                    : variant.getProductColor();

            if (variantRepository.existsByProductColorIdAndSizeExcluding(
                    targetColor_.getId(), targetSize, variantId)) {
                throw new IllegalArgumentException(
                    "Variant already exists for color '" + targetColor + "' and size '" + targetSize + "'");
            }

            if (colorChanged) variant.setProductColor(targetColor_);
        }

        if (dto.getSize()          != null) variant.setSize(dto.getSize());
        if (dto.getStockQuantity() != null) variant.setStockQuantity(dto.getStockQuantity());
        if (dto.getWeightGrams()   != null) variant.setWeightGrams(dto.getWeightGrams());

        return ProductVariantResponseDto.from(variantRepository.save(variant));
    }

    @Transactional
    public void deleteVariant(Long productId, Long variantId, User creator) {
        findProductAndVerifyOwnership(productId, creator);
        variantRepository.delete(findVariant(variantId));
    }

    // ===== Internal =====

    private ProductColor findOrCreateColor(Product product, String color) {
        return productColorRepository
                .findByProductIdAndColor(product.getId(), color)
                .orElseGet(() -> productColorRepository.save(
                    ProductColor.builder()
                        .sku(generateUniqueSku())
                        .color(color)
                        .product(product)
                        .build()));
    }

    private Product findProductAndVerifyOwnership(Long productId, User creator) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
        return product;
    }

    private ProductVariant findVariant(Long variantId) {
        return variantRepository.findById(variantId)
                .orElseThrow(() -> new ProductNotFoundException("Variant not found with id: " + variantId));
    }

    public String generateUniqueSku() {
        String sku;
        do {
            sku = randomSku();
        } while (productColorRepository.existsBySku(sku));
        return sku;
    }

    private static String randomSku() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(SKU_CHARS.charAt(RANDOM.nextInt(SKU_CHARS.length())));
        }
        return sb.toString();
    }
}
