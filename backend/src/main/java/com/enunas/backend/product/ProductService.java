package com.enunas.backend.product;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.ErrorCode;
import com.enunas.backend.exception.ProductDeletionBlockedException;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.media.ProductImageRepository;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.OrderItemRepository;
import com.enunas.backend.product.dto.*;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.product.dto.DisplayPrice;
import com.enunas.backend.product.productlisting.ListingPriceRow;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductColorRepository;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductVariantService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductColorRepository productColorRepository;
    private final ProductImageRepository productImageRepository;
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
     * definition, just as a not-found guard instead of a filter predicate (a list silently omits; a
     * direct lookup 404s). A product is browsable when it is ACTIVE and has an active listing that
     * is either currently in its availability window (live) or on a product whose {@code releaseDate}
     * is still in the future (a "Coming Soon" preview — returned with {@code preview=true} and no
     * price, and rejected by the checkout guard in
     * {@code OrderService.resolveAndValidateListings}).
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
        if (product.getStatus() != ProductStatus.ACTIVE) {
            throw new ProductNotFoundException("No product found with id: " + product.getId());
        }
        boolean live = listingRepository.existsCurrentlyActiveListingByProductId(product.getId());
        boolean preview = product.getReleaseDate() != null
                && product.getReleaseDate().isAfter(LocalDate.now())
                && listingRepository.existsActiveListingByProductId(product.getId());
        if (!live && !preview) {
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

    /**
     * Trims before matching: a SKU arrives pasted, and a trailing space or newline picked up from a
     * PDF or a spreadsheet cell would otherwise stop an otherwise-exact SKU matching anything.
     * Blank input keeps its existing behaviour — it still matches everything; {@code GET /products}
     * is the endpoint that means "list them all".
     */
    @Transactional(readOnly = true)
    public Page<ProductResponseDto> search(String keyword, Pageable pageable) {
        String trimmed = keyword == null ? "" : keyword.trim();
        return toResponsePage(productRepository.search(trimmed, pageable));
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> getProductsByColorFamily(ColorFamily colorFamily, Pageable pageable) {
        return toResponsePage(productRepository.findByColorFamily(colorFamily, pageable));
    }

    @Transactional(readOnly = true)
    public List<ProductResponseDto> getMyProducts(User creator) {
        List<Product> products = productRepository.findByCreator(creator);
        Map<Long, DisplayPrice> prices = lowestActivePrices(priceLookupIds(products));
        return products.stream().map(product -> toResponse(product, prices)).toList();
    }

    /**
     * The statuses a brand owns for its own product. SUSPENDED and REJECTED are admin moderation
     * verdicts and are reachable only through the admin endpoints (hide / reject / approve) — a
     * brand able to set its own status would otherwise just set ACTIVE and undo them.
     */
    private static final Set<ProductStatus> BRAND_SETTABLE_STATUSES =
            EnumSet.of(ProductStatus.ACTIVE, ProductStatus.INACTIVE, ProductStatus.ARCHIVED);

    private static final Set<ProductStatus> MODERATION_STATUSES =
            EnumSet.of(ProductStatus.SUSPENDED, ProductStatus.REJECTED);

    @Transactional
    public ProductResponseDto updateProduct(Long id, UpdateProductDto dto, User creator) {
        Product product = findById(id);
        verifyOwnership(product, creator);

        applyProductUpdates(product, dto);

        // Status is applied here rather than in the shared applyProductUpdates because the brand
        // and admin routes disagree about what may be set: AdminService accepts any status.
        if (dto.getStatus() != null) {
            applyBrandStatusChange(product, dto.getStatus());
        }

        return toResponse(productRepository.save(product));
    }

    /**
     * ARCHIVED is the exit {@link #deleteProduct} points a brand at when a product cannot be hard
     * deleted, and {@code ProductListingRepository.STOREFRONT_VISIBLE} requires ACTIVE, so archiving
     * genuinely takes the product off the storefront. Until this existed the DTO had no status field
     * at all, so that advice named a field the same service rejected as an unknown property.
     */
    private void applyBrandStatusChange(Product product, ProductStatus target) {
        if (!BRAND_SETTABLE_STATUSES.contains(target)) {
            throw new IllegalArgumentException(
                    "A brand may only set a product's status to " + BRAND_SETTABLE_STATUSES
                    + ". " + target + " is set by admin moderation.");
        }
        if (target == ProductStatus.ACTIVE && MODERATION_STATUSES.contains(product.getStatus())) {
            throw new IllegalStateException(
                    "Product " + product.getId() + " is " + product.getStatus()
                    + " by admin moderation and cannot be reactivated by the brand.");
        }
        product.setStatus(target);
    }

    /**
     * Hard-deletes a product the brand created by mistake. Refuses once the product has a history
     * worth keeping, and names what is in the way when it refuses — {@code order_items} and
     * {@code listings} both reach the product through its variants, and letting their foreign keys
     * abort the delete instead produces a bare "Data integrity violation" 409 that tells the brand
     * nothing about what to remove.
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
            throw new ProductDeletionBlockedException(ErrorCode.PRODUCT_HAS_ORDERS,
                    "Product " + id + " has already been ordered and cannot be deleted — its order"
                    + " history depends on it. Set its status to ARCHIVED instead.");
        }
        if (!listingRepository.findByProductId(id).isEmpty()) {
            throw new ProductDeletionBlockedException(ErrorCode.PRODUCT_HAS_LISTINGS,
                    "Product " + id + " still has listings. Remove them first, or set the product's"
                    + " status to ARCHIVED to take it off the storefront.");
        }

        purgeProduct(product);
    }

    /**
     * Deletes a product together with everything that references it, in foreign-key order. Shared
     * with {@code AdminService.deleteProduct}, which reaches the same rows by a different route.
     *
     * <p>Two of those references are invisible from {@link Product}'s cascade rules, and they are
     * why a brand could not delete <em>any</em> product that had ever had a variant:
     *
     * <ul>
     *   <li>{@code product_colors} has no mapped collection on {@link Product} at all, so nothing
     *       cascaded to it — and no other code path ever deleted a {@link ProductColor}. Deleting
     *       the variants first did not help, because creating a variant is what creates the colour
     *       ({@code ProductVariantService.findOrCreateColor}) and deleting one leaves it behind. The
     *       orphan row then aborted the delete as an opaque "Data integrity violation" 409, with
     *       nothing left for the brand to remove.
     *   <li>{@code product_complete_the_look} carries two foreign keys to {@code products}.
     *       Hibernate clears only the owning side ({@code product_id}), so a row where <em>another</em>
     *       product names this one as {@code related_product_id} survives and aborts the delete the
     *       same opaque way.
     * </ul>
     *
     * <p>The order is load-bearing and the flushes are what enforce it: {@code product_variants}
     * points at {@code product_colors}, so the variants must be gone before the colours. Doing this
     * explicitly, rather than by hanging another {@code cascade = ALL} collection off the entity, is
     * deliberate — JPA cascade order follows field declaration order, and an implicit contract of
     * exactly that kind is what let this through in the first place.
     */
    @Transactional
    public void purgeProduct(Product product) {
        Long id = product.getId();

        for (Product referrer : productRepository.findReferencingCompleteTheLook(id)) {
            referrer.getCompleteTheLookProducts().remove(product);
        }

        variantRepository.deleteAll(variantRepository.findByProductId(id));
        variantRepository.flush();

        // Images must go before colours: product_images.product_color_id is ON DELETE SET NULL, so
        // deleting a colourway while its tagged images survive collapses every colour's primary into
        // the shared group, where two or more collide on uq_product_images_primary_shared and abort
        // the delete with a bare DataIntegrityViolationException outside the try below. The product
        // delete would cascade these rows away anyway; their S3 objects are already orphaned by this
        // path today, so nothing is lost by removing them here.
        productImageRepository.deleteByProductId(id);
        productImageRepository.flush();

        productColorRepository.deleteAll(productColorRepository.findByProductId(id));
        productColorRepository.flush();

        try {
            productRepository.delete(product);
            productRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            // Something references the product that neither the guards above nor this purge knows
            // about. Name the product and the way out: the generic handler's bare "Data integrity
            // violation" is what sent brands round the delete-then-archive loop with no exit.
            throw new ProductDeletionBlockedException(ErrorCode.PRODUCT_REFERENCED,
                    "Product " + id + " is still referenced by other records and cannot be deleted."
                    + " Set its status to ARCHIVED to take it off the storefront.", ex);
        }
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

    private ProductResponseDto toResponse(Product product, Map<Long, DisplayPrice> prices) {
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
     * The price to display per product, in one query: the cheapest sellable listing's current
     * price, paired with that same listing's list price when it is a reduction.
     *
     * <p>Reducing in Java rather than in SQL is what keeps the pair honest — the strikethrough has
     * to be the chosen listing's own list price, not the lowest list price across all of them (see
     * {@link ListingPriceRow}). Ids with no sellable listing are absent from the map, and
     * {@code map.get} then yields the null the DTO renders as "no price".
     */
    private Map<Long, DisplayPrice> lowestActivePrices(Set<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();

        Map<Long, ListingPriceRow> cheapest = new HashMap<>();
        for (ListingPriceRow row : listingRepository.findSellableListingPricesByProductIds(productIds)) {
            if (row.price() == null) continue;
            cheapest.merge(row.productId(), row,
                    (a, b) -> b.current().compareTo(a.current()) < 0 ? b : a);
        }

        Map<Long, DisplayPrice> prices = new HashMap<>();
        cheapest.forEach((productId, row) -> prices.put(productId,
                new DisplayPrice(row.current(), row.isDiscounted() ? row.price() : null)));
        return prices;
    }

    /** Page mapping with the prices for the whole page (and its CTL targets) fetched once. */
    private Page<ProductResponseDto> toResponsePage(Page<Product> page) {
        Map<Long, DisplayPrice> prices = lowestActivePrices(priceLookupIds(page.getContent()));
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
