package com.enunas.backend.product;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.dto.*;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.product.productvariant.ProductVariantService;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductColorRepository productColorRepository;
    private final ProductListingRepository listingRepository;
    private final ProductVariantService variantService;
    private final BrandPartnerRepository brandPartnerRepository;

    @Transactional
    public ProductResponseDto createProduct(CreateProductDto dto, User creator) {
        BrandPartner brand = brandPartnerRepository.findByUser(creator)
                .orElseThrow(() -> new IllegalStateException(
                        "No BrandPartner profile found for user: " + creator.getEmail()));

        validateCatalogueCategoryForUpdate(dto.getCategory(), dto.getCatalogueCategory());

        Product product = Product.builder()
                .name(dto.getName())
                .brand(brand)
                .description(dto.getDescription())
                .inspirationStory(dto.getInspirationStory())
                .category(dto.getCategory())
                .gender(dto.getGender())
                .productType(dto.getProductType())
                .material(dto.getMaterial())
                .originCountry(dto.getOriginCountry())
                .careInstructions(dto.getCareInstructions())
                .collectionName(dto.getCollectionName())
                .releaseDate(dto.getReleaseDate())
                .returnPeriodDays(dto.getReturnPeriodDays())
                .status(ProductStatus.ACTIVE)
                .creator(creator)
                .completeTheLookEnabled(Boolean.TRUE.equals(dto.getCompleteTheLookEnabled()))
                .build();

        if (dto.getCatalogueCategory() != null) {
            product.getCatalogueCategory().addAll(dto.getCatalogueCategory());
        }

        Product saved = productRepository.save(product);

        createVariantsGroupedByColor(saved, dto.getVariants());

        if (Boolean.TRUE.equals(dto.getCompleteTheLookEnabled()) && dto.getCompleteTheLookProductIds() != null) {
            applyCompleteTheLook(saved, dto.getCompleteTheLookProductIds());
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductBySku(String sku) {
        ProductColor color = productColorRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException("No product found with SKU: " + sku));
        return toResponse(color.getProduct());
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getProductsByCategory(ProductCategory category, Pageable pageable) {
        return productRepository.findByCategory(category, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getActiveProducts(Pageable pageable) {
        return productRepository.findByStatus(ProductStatus.ACTIVE, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> search(String keyword, Pageable pageable) {
        return productRepository.search(keyword, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public List<ProductResponseDto> getMyProducts(User creator) {
        return productRepository.findByCreator(creator).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ProductResponseDto updateProduct(Long id, UpdateProductDto dto, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);

        applyProductUpdates(product, dto);

        return toResponse(productRepository.save(product));
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

    // ===== Shared update logic (used by ProductService and AdminService) =====

    public void applyProductUpdates(Product product, UpdateProductDto dto) {
        if (dto.getName()             != null) product.setName(dto.getName());
        if (dto.getDescription()      != null) product.setDescription(dto.getDescription());
        if (dto.getInspirationStory() != null) product.setInspirationStory(dto.getInspirationStory());
        if (dto.getProductType()      != null) product.setProductType(dto.getProductType());
        if (dto.getGender()           != null) product.setGender(dto.getGender());
        if (dto.getMaterial()         != null) product.setMaterial(dto.getMaterial());
        if (dto.getOriginCountry()    != null) product.setOriginCountry(dto.getOriginCountry());
        if (dto.getCareInstructions() != null) product.setCareInstructions(dto.getCareInstructions());
        if (dto.getCollectionName()   != null) product.setCollectionName(dto.getCollectionName());
        if (dto.getReleaseDate()      != null) product.setReleaseDate(dto.getReleaseDate());
        if (dto.getReturnPeriodDays() != null) product.setReturnPeriodDays(dto.getReturnPeriodDays());

        if (dto.getCategory() != null) {
            product.setCategory(dto.getCategory());
        }

        if (dto.getCatalogueCategory() != null) {
            ProductCategory effectiveCategory = dto.getCategory() != null
                    ? dto.getCategory()
                    : product.getCategory();
            validateCatalogueCategoryForUpdate(effectiveCategory, dto.getCatalogueCategory());
            product.getCatalogueCategory().clear();
            product.getCatalogueCategory().addAll(dto.getCatalogueCategory());
        }

        if (dto.getCompleteTheLookEnabled() != null) {
            product.setCompleteTheLookEnabled(dto.getCompleteTheLookEnabled());
        }

        if (dto.getCompleteTheLookProductIds() != null) {
            applyCompleteTheLook(product, dto.getCompleteTheLookProductIds());
        }
    }

    // ===== Internal =====

    /**
     * Groups incoming variant DTOs by color and creates one {@link ProductColor} (with unique SKU)
     * per distinct color, then creates {@link ProductVariant} per size within that color.
     */
    private void createVariantsGroupedByColor(Product product, List<ProductVariantDto> variantDtos) {
        Map<String, List<ProductVariantDto>> byColor = variantDtos.stream()
                .collect(Collectors.groupingBy(ProductVariantDto::getColor));

        // Validate no duplicate (color, size) in the request itself
        for (Map.Entry<String, List<ProductVariantDto>> entry : byColor.entrySet()) {
            List<String> sizes = entry.getValue().stream().map(ProductVariantDto::getSize).toList();
            if (sizes.size() != new HashSet<>(sizes).size()) {
                throw new IllegalArgumentException(
                    "Duplicate size for color '" + entry.getKey() + "' in the same request");
            }
        }

        for (Map.Entry<String, List<ProductVariantDto>> entry : byColor.entrySet()) {
            ProductColor productColor = productColorRepository.save(
                ProductColor.builder()
                    .sku(variantService.generateUniqueSku())
                    .color(entry.getKey())
                    .product(product)
                    .build());

            for (ProductVariantDto vDto : entry.getValue()) {
                ProductVariant variant = variantRepository.save(
                    ProductVariant.builder()
                        .productColor(productColor)
                        .size(vDto.getSize())
                        .stockQuantity(vDto.getStockQuantity())
                        .weightGrams(vDto.getWeightGrams())
                        .product(product)
                        .build());
                productColor.getVariants().add(variant);
                product.addVariant(variant);
            }
        }
    }

    private void applyCompleteTheLook(Product product, Set<Long> productIds) {
        if (productIds == null) return;

        if (Boolean.TRUE.equals(product.getCompleteTheLookEnabled())) {
            if (productIds.size() < 1 || productIds.size() > 4) {
                throw new IllegalArgumentException(
                    "completeTheLookProductIds must have 1 to 4 items when completeTheLookEnabled is true");
            }
            if (product.getId() != null && productIds.contains(product.getId())) {
                throw new IllegalArgumentException("A product cannot reference itself in Complete The Look");
            }
        }

        Set<Product> related = new HashSet<>();
        for (Long relatedId : productIds) {
            related.add(productRepository.findById(relatedId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found: " + relatedId)));
        }

        product.getCompleteTheLookProducts().clear();
        product.getCompleteTheLookProducts().addAll(related);
    }

    private void validateCatalogueCategoryForUpdate(ProductCategory category, List<?> catalogueCategory) {
        if (category == ProductCategory.CLOTHING) {
            if (catalogueCategory == null || catalogueCategory.isEmpty() || catalogueCategory.size() > 3) {
                throw new IllegalArgumentException(
                    "catalogueCategory must have 1 to 3 values when category is CLOTHING");
            }
        }
    }

    /**
     * Maps a Product to a response DTO with CTL card prices populated from the listing table.
     * Each CTL product gets its lowest currently-active listing price (null if no active listing).
     */
    private ProductResponseDto toResponse(Product product) {
        Map<Long, BigDecimal> ctlPrices = product.getCompleteTheLookProducts().stream()
                .collect(Collectors.toMap(
                    Product::getId,
                    p -> listingRepository.findLowestActivePriceByProductId(p.getId()).orElse(null),
                    (a, b) -> a));
        return ProductResponseDto.from(product, ctlPrices::get);
    }

    private void verifyOwnership(Product product, User creator) {
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
    }
}
