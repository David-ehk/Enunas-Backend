package com.enunas.backend.product;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.OrderItemRepository;
import com.enunas.backend.product.dto.*;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.product.productlisting.ProductPriceRow;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductVariantService;
import com.enunas.backend.user.Role;
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
    private final MediaUrlResolver mediaUrlResolver;
    private final OrderItemRepository orderItemRepository;

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

        product.setSlug(generateUniqueSlug(dto.getName()));

        Product saved = productRepository.save(product);

        createVariantsGroupedByColor(saved, dto.getVariants());

        if (Boolean.TRUE.equals(dto.getCompleteTheLookEnabled()) && dto.getCompleteTheLookProductIds() != null) {
            applyCompleteTheLook(saved, dto.getCompleteTheLookProductIds());
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductById(Long id, User viewer) {
        Product product = findById(id);
        assertBrowsable(product, viewer);
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductBySku(String sku, User viewer) {
        ProductColor color = productColorRepository.findBySku(sku)
                .orElseThrow(() -> new ProductNotFoundException("No product found with SKU: " + sku));
        Product product = color.getProduct();
        assertBrowsable(product, viewer);
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponseDto getProductBySlug(String slug, User viewer) {
        Product product = productRepository.findBySlug(slug)
                .orElseThrow(() -> new ProductNotFoundException("No product found with slug: " + slug));
        assertBrowsable(product, viewer);
        return toResponse(product);
    }

    /**
     * The single-product (PDP) mirror of the browse-list gate in {@link ProductRepository} — same
     * "ACTIVE status AND at least one currently-active listing" definition, just as a not-found
     * guard instead of a filter predicate (a list silently omits; a direct lookup 404s).
     *
     * The product's own brand and admins are exempt: {@code createProduct} persists a product with
     * variants but NO listing (listings are created separately via /listings), so gating the owner
     * too would 404 a brand on the detail page of the product they just created — the gate exists
     * to keep unbuyable products off the storefront, not to hide a brand's own catalogue from it.
     * {@code viewer} is null for anonymous storefront traffic (GET /products/** is permitAll).
     */
    private void assertBrowsable(Product product, User viewer) {
        if (viewer != null && (viewer.getRole() == Role.ADMIN
                || (product.getCreator() != null && product.getCreator().getId().equals(viewer.getId())))) {
            return;
        }
        boolean hasActiveListing = listingRepository.existsCurrentlyActiveListingByProductId(product.getId());
        if (product.getStatus() != ProductStatus.ACTIVE || !hasActiveListing) {
            throw new ProductNotFoundException("No product found with id: " + product.getId());
        }
    }

    // NOTE: there is deliberately no ungated "all products" method here. One existed, calling
    // productRepository.findAll straight through, and nothing called it — ProductController's
    // GET /products maps to getActiveProducts. Left in place it was a loaded gun: a public method
    // named getAllProducts, on a service whose every other browse method applies the
    // ACTIVE-plus-sellable-listing gate, returning SUSPENDED, REJECTED and unlisted products to
    // whoever called it next. Admin oversight has its own path (AdminService.getAllProducts).

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getProductsByCategory(ProductCategory category, Pageable pageable) {
        return toResponsePage(productRepository.findByCategory(category, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getActiveProducts(Pageable pageable) {
        return toResponsePage(productRepository.findByStatus(ProductStatus.ACTIVE, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> search(String keyword, Pageable pageable) {
        return toResponsePage(productRepository.search(keyword, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getProductsByColorFamily(ColorFamily colorFamily, Pageable pageable) {
        return toResponsePage(productRepository.findByColorFamily(colorFamily, pageable));
    }

    @Transactional(readOnly = true)
    public List<ProductResponseDto> getMyProducts(User creator) {
        List<Product> products = productRepository.findByCreator(creator);
        Map<Long, BigDecimal> prices = lowestActivePrices(priceLookupIds(products));
        return products.stream().map(product -> toResponse(product, prices)).toList();
    }

    @Transactional
    public ProductResponseDto updateProduct(Long id, UpdateProductDto dto, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);

        applyProductUpdates(product, dto);

        return toResponse(productRepository.save(product));
    }

    /**
     * Hard-deletes a product the brand created by mistake. Refuses once the product has a history
     * worth keeping, because the alternative is worse than a refusal: the delete cascades to
     * variants and colours, and the foreign keys from {@code order_items} and {@code listings} then
     * abort it as a bare "Data integrity violation" 409 that tells the brand nothing about which of
     * their products is stuck or what to do instead.
     *
     * <p>Order history is the hard stop — {@link com.enunas.backend.order.OrderItem} rows carry the
     * frozen money snapshots behind the commercial record, and they are only readable while the
     * variant they point at survives. Archiving is the operation for a product that has sold.
     */
    @Transactional
    public void deleteProduct(Long id, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);

        if (orderItemRepository.existsByVariant_Product_Id(id)) {
            throw new IllegalStateException(
                    "Product " + id + " has already been ordered and cannot be deleted — its order"
                    + " history depends on it. Set its status to ARCHIVED instead.");
        }
        if (!listingRepository.findByProductId(id).isEmpty()) {
            throw new IllegalStateException(
                    "Product " + id + " still has listings. Remove them first, or set the product's"
                    + " status to ARCHIVED to take it off the storefront.");
        }

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
            long distinctFamilies = entry.getValue().stream()
                    .map(ProductVariantDto::getColorFamily).distinct().count();
            if (distinctFamilies > 1) {
                throw new IllegalArgumentException(
                    "All variants with color '" + entry.getKey() + "' must share the same colorFamily");
            }
        }

        for (Map.Entry<String, List<ProductVariantDto>> entry : byColor.entrySet()) {
            ColorFamily colorFamily = entry.getValue().get(0).getColorFamily();
            ProductColor productColor = productColorRepository.save(
                ProductColor.builder()
                    .sku(variantService.generateUniqueSku())
                    .color(entry.getKey())
                    .colorFamily(colorFamily)
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
     * Maps a Product to a response DTO with its own price and its CTL card prices, all read from
     * one batched aggregate.
     *
     * <p>This used to collect the CTL prices with {@code Collectors.toMap(id, price, ...)} over
     * {@code findLowestActivePriceByProductId(...).orElse(null)}. That throws: {@code toMap} is
     * backed by {@code HashMap.merge}, which rejects a null VALUE outright, and a CTL target with
     * no sellable listing produces exactly that. It was reachable from the ordinary path —
     * createProduct returns through here, and a brand-new product has no listing yet (listings are
     * created separately, see assertBrowsable), so pointing Complete-The-Look at anything not yet
     * on sale answered 500 having already saved the product. Reading from a map that simply has no
     * entry for those products is both the fix and the reason there is nothing left to null-check.
     */
    private ProductResponseDto toResponse(Product product) {
        return toResponse(product, lowestActivePrices(priceLookupIds(List.of(product))));
    }

    private ProductResponseDto toResponse(Product product, Map<Long, BigDecimal> prices) {
        return ProductResponseDto.from(product, prices.get(product.getId()), prices::get, mediaUrlResolver);
    }

    /** Every product id a response needs a price for: the products themselves, plus their CTL
     *  targets, which are rendered as cards with their own prices. */
    private Set<Long> priceLookupIds(Collection<Product> products) {
        Set<Long> ids = new HashSet<>();
        for (Product product : products) {
            ids.add(product.getId());
            for (Product related : product.getCompleteTheLookProducts()) {
                ids.add(related.getId());
            }
        }
        return ids;
    }

    /**
     * Lowest sellable price per product, in one query. Ids with no sellable listing are absent from
     * the map, and {@code map.get} then yields the same null the DTO has always rendered as "no
     * price" — so absence is carried, never a null value.
     */
    private Map<Long, BigDecimal> lowestActivePrices(Set<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return listingRepository.findLowestActivePricesByProductIds(productIds).stream()
                .filter(row -> row.price() != null)
                .collect(Collectors.toMap(ProductPriceRow::productId, ProductPriceRow::price));
    }

    /** Page mapping with the prices for the whole page (and its CTL targets) fetched once. */
    private Page<ProductResponseDto> toResponsePage(Page<Product> page) {
        Map<Long, BigDecimal> prices = lowestActivePrices(priceLookupIds(page.getContent()));
        return page.map(product -> toResponse(product, prices));
    }

    /**
     * Builds a unique, stable slug from the product name. The base mirrors the frontend
     * generateSlug(); on collision we append -2, -3, ... so links stay unambiguous. Slugs are
     * assigned once at creation and intentionally not changed on rename (URL stability / SEO).
     */
    private String generateUniqueSlug(String name) {
        String base = SlugUtil.baseSlug(name);
        if (base.isEmpty()) base = "produkt";
        String candidate = base;
        int n = 2;
        while (productRepository.existsBySlug(candidate)) {
            candidate = base + "-" + n++;
        }
        return candidate;
    }

    private void verifyOwnership(Product product, User creator) {
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
    }
}
