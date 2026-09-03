package com.enunas.backend.product.productlisting;

import com.enunas.backend.common.MoneyMath;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.ListingResponseDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductListingService {

    private final ProductListingRepository productListingRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @Value("${enunas.vat.product-rate:0.19}")
    private BigDecimal vatRateProduct;

    @Transactional
    public ListingResponseDto createListing(Long productId, CreateListingDto dto, User creator) {
        Product product = findProductAndVerifyOwnership(productId, creator);
        ProductVariant variant = variantRepository.findById(dto.getVariantId())
                .orElseThrow(() -> new ProductNotFoundException("Variant not found with id: " + dto.getVariantId()));

        if (!variant.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException("Variant does not belong to this product");
        }

        ProductListing productListing = ProductListing.builder()
                .product(product)
                .variant(variant)
                .priceInputMode(dto.getPriceInputMode())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "EUR")
                .region(dto.getRegion())
                .dropDate(dto.getDropDate())
                .availableFrom(dto.getAvailableFrom())
                .availableUntil(dto.getAvailableUntil())
                .build();

        // Derive the gross/net pair from the entered figure(s) under the chosen mode.
        setPricePair(productListing, dto.getPrice(), dto.getPriceInputMode(), false);
        if (dto.getDiscountPrice() != null) {
            setPricePair(productListing, dto.getDiscountPrice(), dto.getPriceInputMode(), true);
        }

        return ListingResponseDto.from(productListingRepository.save(productListing));
    }

    /**
     * A single listing. Gated exactly like the PDP: the owning brand and admins see it whatever
     * state it is in, everyone else only if it is storefront-visible. It used to be an ungated
     * {@code findById}, so an anonymous caller walking listing ids could read the name, SKU, live
     * stock, price breakdown and launch time of a product that was suspended, rejected, or simply
     * not released yet.
     */
    @Transactional(readOnly = true)
    public ListingResponseDto getListingById(Long listingId, User viewer) {
        ProductListing listing = findById(listingId);
        if (!canSeeUnpublished(listing.getProduct(), viewer)
                && productListingRepository.findStorefrontVisibleById(listingId).isEmpty()) {
            throw new ProductNotFoundException("Listing not found with id: " + listingId);
        }
        return ListingResponseDto.from(listing);
    }

    /**
     * One product's listings. The owning brand and admins get the management view — <em>every</em>
     * listing, active or not, in its window or not; storefront traffic gets only what is genuinely
     * buyable. An empty list is the intended answer for a hidden product — the PDP deliberately
     * still renders and shows "price unavailable" rather than 404ing.
     *
     * <p>The owner branch used to be {@code findByProductIdAndActive(id, true)}, which hid a
     * deactivated listing from its own brand. Since {@code UpdateListingDto} exposes {@code active},
     * that was a one-way trap: a brand could switch a listing off and then had no way to find it
     * again, because reactivating it needs a listing id the dashboard no longer showed anywhere.
     * {@link ListingResponseDto} carries {@code active}, so telling live and switched-off listings
     * apart is the client's job, not something to enforce by withholding the row.
     */
    @Transactional(readOnly = true)
    public List<ListingResponseDto> getListingsByProduct(Long productId, User viewer) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        List<ProductListing> listings = canSeeUnpublished(product, viewer)
                ? productListingRepository.findByProductId(productId)
                : productListingRepository.findStorefrontVisibleByProductId(productId);
        return listings.stream().map(ListingResponseDto::from).toList();
    }

    /**
     * The public listing feed, optionally narrowed to a region.
     *
     * <p>A missing {@code region} means "any region". It previously fell through to
     * {@code findByRegionAndActive(null, true)}, which compiles to SQL {@code region = NULL} and so
     * matched nothing at all — the default form of this endpoint returned an empty list. The two
     * nulls are different questions: the caller not filtering, versus a listing sold in every
     * region.
     */
    @Transactional(readOnly = true)
    public Page<ListingResponseDto> getActiveListingsByRegion(String region, Pageable pageable) {
        return productListingRepository.findStorefrontVisible(region, pageable)
                .map(ListingResponseDto::from);
    }

    /**
     * The owning brand and admins see listings the storefront hides. Mirrors the exemption in
     * {@code ProductService.assertBrowsable}: the gate exists to keep unbuyable products off the
     * storefront, not to hide a brand's own catalogue from it. {@code viewer} is null for anonymous
     * traffic (these reads are permitAll).
     */
    private boolean canSeeUnpublished(Product product, User viewer) {
        if (viewer == null) return false;
        return viewer.getRole() == Role.ADMIN
                || (product.getCreator() != null && product.getCreator().getId().equals(viewer.getId()));
    }

    @Transactional
    public ListingResponseDto updateListing(Long productId, Long listingId, UpdateListingDto dto, User creator) {
        findProductAndVerifyOwnership(productId, creator);
        ProductListing productListing = findById(listingId);

        // Recompute the gross/net pair whenever a price field OR the input mode changes, so a
        // partial update never leaves a stale net that disagrees with its gross.
        PriceInputMode oldMode = productListing.getPriceInputMode();
        PriceInputMode effectiveMode = dto.getPriceInputMode() != null ? dto.getPriceInputMode() : oldMode;
        boolean modeChanged = dto.getPriceInputMode() != null && dto.getPriceInputMode() != oldMode;

        if (dto.getPriceInputMode() != null) productListing.setPriceInputMode(effectiveMode);

        if (dto.getPrice() != null) {
            setPricePair(productListing, dto.getPrice(), effectiveMode, false);
        } else if (modeChanged) {
            // Reinterpret the originally-entered figure under the new mode.
            setPricePair(productListing, enteredValue(productListing, oldMode, false), effectiveMode, false);
        }

        if (dto.getDiscountPrice() != null) {
            setPricePair(productListing, dto.getDiscountPrice(), effectiveMode, true);
        } else if (modeChanged && productListing.getDiscountPrice() != null) {
            setPricePair(productListing, enteredValue(productListing, oldMode, true), effectiveMode, true);
        }

        if (dto.getActive() != null) productListing.setActive(dto.getActive());
        if (dto.getRegion() != null) productListing.setRegion(dto.getRegion());
        if (dto.getDropDate() != null) productListing.setDropDate(dto.getDropDate());
        if (dto.getAvailableFrom() != null) productListing.setAvailableFrom(dto.getAvailableFrom());
        if (dto.getAvailableUntil() != null) productListing.setAvailableUntil(dto.getAvailableUntil());

        return ListingResponseDto.from(productListingRepository.save(productListing));
    }

    /**
     * Sets the gross + net pair on the listing from a single entered figure interpreted under
     * {@code mode}. NET ⇒ gross is derived; GROSS ⇒ net is derived. Defaults to GROSS if the
     * mode is somehow absent (legacy rows). {@code discount=true} targets the sale-price columns.
     */
    private void setPricePair(ProductListing listing, BigDecimal entered, PriceInputMode mode, boolean discount) {
        BigDecimal gross;
        BigDecimal net;
        if (mode == PriceInputMode.NET) {
            net = MoneyMath.round2(entered);
            gross = MoneyMath.grossFromNet(entered, vatRateProduct);
        } else {
            gross = MoneyMath.round2(entered);
            net = MoneyMath.netFromGross(entered, vatRateProduct);
        }
        if (discount) {
            listing.setDiscountPrice(gross);
            listing.setDiscountPriceNet(net);
        } else {
            listing.setPrice(gross);
            listing.setPriceNet(net);
        }
    }

    /** The figure the brand originally entered, recovered from the stored pair under {@code mode}. */
    private BigDecimal enteredValue(ProductListing listing, PriceInputMode mode, boolean discount) {
        if (mode == PriceInputMode.NET) {
            return discount ? listing.getDiscountPriceNet() : listing.getPriceNet();
        }
        return discount ? listing.getDiscountPrice() : listing.getPrice();
    }

    @Transactional
    public void deleteListing(Long productId, Long listingId, User creator) {
        findProductAndVerifyOwnership(productId, creator);
        productListingRepository.delete(findById(listingId));
    }

    private ProductListing findById(Long listingId) {
        return productListingRepository.findById(listingId)
                .orElseThrow(() -> new ProductNotFoundException("Listing not found with id: " + listingId));
    }

    private Product findProductAndVerifyOwnership(Long productId, User creator) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
        return product;
    }
}
