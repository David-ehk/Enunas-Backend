package com.enunas.backend.product.productlisting;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductListingRepository extends JpaRepository<ProductListing, Long> {

    /**
     * "Sellable right now", as one predicate instead of four hand-copied ones.
     *
     * <p>Both timestamps are nullable and {@code createListing} defaults neither, so a listing with
     * no window at all is the ordinary case, not an edge case — NULL has to read as "no bound",
     * exactly as {@link ProductListing#isCurrentlyActive()} treats it. Two queries here previously
     * wrote the {@code availableFrom} half without its NULL guard and so matched only listings that
     * had been given an explicit start date; sharing the text is what stops that drifting again.
     *
     * <p>Interface fields are implicitly {@code static final}, so this concatenates into the
     * compile-time constant that {@code @Query} requires. The alias must be {@code l}.
     */
    String CURRENTLY_SELLABLE =
            "l.active = true "
            + "AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP) "
            + "AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP)";

    /**
     * What an anonymous storefront caller is allowed to see: sellable <em>and</em> belonging to a
     * product that passed moderation.
     *
     * <p>{@link #CURRENTLY_SELLABLE} alone is not enough. The listing tables know nothing about
     * {@code products.status}, so a listing whose product an admin later SUSPENDED or REJECTED is
     * still perfectly "sellable" by its own columns. Every other public read pairs the two checks —
     * the PLP queries in {@code ProductRepository} and {@code ProductService.assertBrowsable} both
     * require ACTIVE status <em>and</em> a currently-active listing — and this is that same gate for
     * the listing-shaped endpoints.
     */
    String STOREFRONT_VISIBLE =
            CURRENTLY_SELLABLE + " AND l.product.status = com.enunas.backend.product.ProductStatus.ACTIVE";

    /**
     * The public listing feed. {@code region} null means "any region" — the caller left the filter
     * off — which is a different thing from a listing whose own {@code region} is null, and that
     * one means "sold everywhere" and so matches every region asked for.
     *
     * <p>Paged deliberately: without a region this spans the entire catalogue.
     */
    @Query("SELECT l FROM ProductListing l "
           + "WHERE (:region IS NULL OR l.region IS NULL OR l.region = :region) AND " + STOREFRONT_VISIBLE)
    Page<ProductListing> findStorefrontVisible(@Param("region") String region, Pageable pageable);

    /** Storefront view of one product's listings; empty when the product is not browsable. */
    @Query("SELECT l FROM ProductListing l WHERE l.product.id = :productId AND " + STOREFRONT_VISIBLE)
    List<ProductListing> findStorefrontVisibleByProductId(@Param("productId") Long productId);

    /** Storefront lookup of a single listing; empty rather than exposing a hidden product's price. */
    @Query("SELECT l FROM ProductListing l WHERE l.id = :listingId AND " + STOREFRONT_VISIBLE)
    Optional<ProductListing> findStorefrontVisibleById(@Param("listingId") Long listingId);

    /**
     * Every listing for a product, regardless of active flag or availability window — the
     * brand-facing management view. Use {@link #findStorefrontVisibleByProductId} or
     * {@link #findLowestActivePriceByProductId} for anything a customer sees.
     */
    List<ProductListing> findByProductId(Long productId);

    List<ProductListing> findByVariantId(Long variantId);

    Optional<ProductListing> findByVariantIdAndActiveTrue(Long variantId);

    /** Active flag only: still returns listings whose availability window has closed. */
    List<ProductListing> findByProductIdAndActive(Long productId, boolean active);

    /** Active flag only: still returns listings whose availability window has closed. */
    List<ProductListing> findByRegionAndActive(String region, boolean active);

    @Query("SELECT l FROM ProductListing l WHERE l.variant.id = :variantId AND " + CURRENTLY_SELLABLE)
    Optional<ProductListing> findCurrentlyActiveByVariantId(@Param("variantId") Long variantId);

    /** Regional lookup; a listing with {@code region} NULL is sold in every region. */
    @Query("SELECT l FROM ProductListing l WHERE l.variant.id = :variantId "
           + "AND (l.region IS NULL OR l.region = :region) AND " + CURRENTLY_SELLABLE)
    Optional<ProductListing> findCurrentlyActiveByVariantIdAndRegion(@Param("variantId") Long variantId,
                                                                    @Param("region") String region);

    /** Lowest effective price across all currently active listings for a product. */
    @Query("SELECT MIN(CASE WHEN l.discountPrice IS NOT NULL THEN l.discountPrice ELSE l.price END) "
           + "FROM ProductListing l WHERE l.product.id = :productId AND " + CURRENTLY_SELLABLE)
    Optional<BigDecimal> findLowestActivePriceByProductId(@Param("productId") Long productId);

    /**
     * Every sellable listing for these products, as (product, price, discountPrice) rows — one
     * query for a whole page of products and their Complete-The-Look targets, replacing the
     * per-product aggregate that used to run once per card.
     *
     * <p>Returns rows rather than a MIN() aggregate on purpose: the caller picks the cheapest
     * listing and keeps <em>that</em> listing's list price for the strikethrough. See
     * {@link ListingPriceRow} for why two aggregates cannot do this correctly. A product with no
     * sellable listing contributes no rows, which the caller reads as "no price".
     */
    @Query("SELECT new com.enunas.backend.product.productlisting.ListingPriceRow("
           + "l.product.id, l.price, l.discountPrice) "
           + "FROM ProductListing l WHERE l.product.id IN :productIds AND " + CURRENTLY_SELLABLE)
    List<ListingPriceRow> findSellableListingPricesByProductIds(@Param("productIds") Collection<Long> productIds);

    /** Backs the storefront PDP gate (ProductService.assertBrowsable) — true iff this product has
     *  at least one currently-active, in-window listing. Same predicate as
     *  {@link #findLowestActivePriceByProductId}, as an existence check instead of a price. */
    @Query("SELECT COUNT(l) > 0 FROM ProductListing l "
           + "WHERE l.product.id = :productId AND " + CURRENTLY_SELLABLE)
    boolean existsCurrentlyActiveListingByProductId(@Param("productId") Long productId);
}
