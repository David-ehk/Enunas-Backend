package com.enunas.backend.product;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.dto.*;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.product.productvariant.ProductVariantService;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductVariantService variantService;
    private final BrandPartnerRepository brandPartnerRepository;

    @Transactional
    public ProductResponseDto createProduct(CreateProductDto dto, User creator) {
        BrandPartner brand = brandPartnerRepository.findByUser(creator)
                .orElseThrow(() -> new IllegalStateException(
                        "No BrandPartner profile found for user: " + creator.getEmail() +
                        ". Register a brand profile before creating products."));

        Product product = Product.builder()
                .name(dto.getName())
                .brand(brand)
                .description(dto.getDescription())
                .inspirationStory(dto.getInspirationStory())
                .category(dto.getCategory())
                .catalogueCategory(dto.getCatalogueCategory())
                .gender(dto.getGender())
                .material(dto.getMaterial())
                .originCountry(dto.getOriginCountry())
                .careInstructions(dto.getCareInstructions())
                .collectionName(dto.getCollectionName())
                .releaseDate(dto.getReleaseDate())
                .returnPeriodDays(dto.getReturnPeriodDays())
                .status(ProductStatus.ACTIVE)
                .creator(creator)
                .build();

        Product saved = productRepository.save(product);

        List<ProductVariant> variants = dto.getVariants().stream()
                .map(v -> ProductVariant.builder()
                        .sku(variantService.generateUniqueSku())
                        .color(v.getColor())
                        .size(v.getSize())
                        .stockQuantity(v.getStockQuantity())
                        .weightGrams(v.getWeightGrams())
                        .product(saved)
                        .build())
                .toList();

        variantRepository.saveAll(variants);
        variants.forEach(saved::addVariant);

        return ProductResponseDto.from(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductById(Long id) {
        return ProductResponseDto.from(findById(id));
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductBySku(String sku) {
        ProductVariant variant = variantRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException("No product found with SKU: " + sku));
        return ProductResponseDto.from(variant.getProduct());
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(ProductResponseDto::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getProductsByCategory(ProductCategory category, Pageable pageable) {
        return productRepository.findByCategory(category, pageable).map(ProductResponseDto::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getActiveProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ACTIVE, pageable).map(ProductResponseDto::from);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> search(String keyword, Pageable pageable) {
        return productRepository.search(keyword, pageable).map(ProductResponseDto::from);
    }

    @Transactional(readOnly = true)
    public List<ProductResponseDto> getMyProducts(User creator) {
        return productRepository.findByCreator(creator).stream()
                .map(ProductResponseDto::from)
                .toList();
    }

    @Transactional
    public ProductResponseDto updateProduct(Long id, UpdateProductDto dto, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);

        if (dto.getName() != null) product.setName(dto.getName());
        if (dto.getDescription() != null) product.setDescription(dto.getDescription());
        if (dto.getInspirationStory() != null) product.setInspirationStory(dto.getInspirationStory());
        if (dto.getCategory() != null) product.setCategory(dto.getCategory());
        if (dto.getCatalogueCategory() != null) product.setCatalogueCategory(dto.getCatalogueCategory());
        if (dto.getGender() != null) product.setGender(dto.getGender());
        if (dto.getMaterial() != null) product.setMaterial(dto.getMaterial());
        if (dto.getOriginCountry() != null) product.setOriginCountry(dto.getOriginCountry());
        if (dto.getCareInstructions() != null) product.setCareInstructions(dto.getCareInstructions());
        if (dto.getCollectionName() != null) product.setCollectionName(dto.getCollectionName());
        if (dto.getReleaseDate() != null) product.setReleaseDate(dto.getReleaseDate());
        if (dto.getReturnPeriodDays() != null) product.setReturnPeriodDays(dto.getReturnPeriodDays());

        return ProductResponseDto.from(productRepository.save(product));
    }

    @Transactional
    public void deleteProduct(Long id, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);
        productRepository.delete(product);
    }

    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + id));
    }

    private void verifyOwnership(Product product, User creator) {
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
    }
}
