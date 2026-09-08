package com.enunas.backend.product;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.user.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCreator(User creator);

    java.util.Optional<Product> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Page<Product> findByCategoryAndStatus(ProductCategory category, ProductStatus status, Pageable pageable);

    boolean existsByCollectionName(String collectionName);

    /**
     * The products that name {@code relatedProductId} in their complete-the-look set — i.e. the
     * inverse side of the self-referential join table.
     *
     * <p>{@code product_complete_the_look} has a foreign key to {@code products} on both of its
     * columns, but {@code Product.completeTheLookProducts} is the owning side, so deleting a product
     * clears only the rows where it is {@code product_id}. Rows naming it as {@code related_product_id}
     * survive and abort the delete. ProductService.purgeProduct uses this to clear them from the
     * owning side first.
     */
    @Query("SELECT p FROM Product p JOIN p.completeTheLookProducts r WHERE r.id = :relatedProductId")
    List<Product> findReferencingCompleteTheLook(@Param("relatedProductId") Long relatedProductId);

    // ===== Public storefront browse (PLP) — every method below requires ACTIVE moderation status
    // AND an active listing (active=true) that is EITHER currently within its availability window
    // (live) OR on a product whose releaseDate is still in the future (a "Coming Soon" preview:
    // returned, flagged preview=true, priced null, and rejected by the checkout guard
    // OrderService.resolveAndValidateListings). A product whose only listing(s) a brand deactivated
    // must disappear from every one of these. See ProductService.assertBrowsable for the identical
    // PDP-side (single-product) gate, and ProductListingRepository.CURRENTLY_SELLABLE for the
    // listing-side sibling. =====

    /**
     * The active-listing EXISTS shared by all four PLP queries below — kept in one place so the
     * "live OR preview" predicate cannot drift between them (the four used to hand-copy the window
     * check, and two once dropped its NULL guard). Uses the outer alias {@code p} and its own
     * {@code l}; every caller still writes its own {@code p.status} / {@code p.category} / keyword
     * predicate.
     */
    String STOREFRONT_BROWSABLE_LISTING =
            "EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = p AND l.active = true "
            + "AND ((p.releaseDate IS NOT NULL AND p.releaseDate > CURRENT_DATE) "
            + "     OR ((l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP) "
            + "         AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))))";

    @Query("SELECT p FROM Product p WHERE p.status = :status AND " + STOREFRONT_BROWSABLE_LISTING)
    Page<Product> findByStatus(@Param("status") ProductStatus status, Pageable pageable);

    @Query("SELECT p FROM Product p WHERE p.category = :category AND p.status = 'ACTIVE' AND "
           + STOREFRONT_BROWSABLE_LISTING)
    Page<Product> findByCategory(@Param("category") ProductCategory category, Pageable pageable);

    /**
     * Storefront search across name, brand, description and SKU.
     *
     * <p>SKU is matched as a substring, not by equality, and that is the point: someone searching a
     * SKU has read it off a care label, an invoice or a parcel, so it arrives partial, in the wrong
     * case, or with a stray character. {@code GET /products/sku/{sku}} stays the exact-match lookup
     * for a caller that already knows it holds a whole SKU; this is the human path, where finding
     * the product from most of one is worth more than being strict.
     *
     * <p>The SKU lives on {@link com.enunas.backend.product.productvariant.ProductColor}, one per
     * colour, so this is an EXISTS over a product's colours rather than a column on Product.
     *
     * <p>Deliberately no relevance ordering. A SKU is unique, so an exact one already narrows to a
     * single product; ranking would buy nothing here, and a CASE-based ORDER BY interacts badly
     * with whatever sort the Pageable carries.
     */
    @Query("SELECT p FROM Product p WHERE p.status = 'ACTIVE' "
           + "AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) "
           + "     OR LOWER(p.brand.brandName) LIKE LOWER(CONCAT('%', :keyword, '%')) "
           + "     OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%')) "
           + "     OR EXISTS (SELECT 1 FROM ProductColor pc WHERE pc.product = p "
           + "                AND LOWER(pc.sku) LIKE LOWER(CONCAT('%', :keyword, '%')))) "
           + "AND " + STOREFRONT_BROWSABLE_LISTING)
    Page<Product> search(@Param("keyword") String keyword, Pageable pageable);

    // Restructured from "SELECT pc.product FROM ProductColor pc" to an explicit entity join so the
    // outer alias is `p` and this shares STOREFRONT_BROWSABLE_LISTING with the other three. Product
    // has no inverse `colors` collection, hence JOIN ... ON.
    @Query(
        value = "SELECT DISTINCT p FROM Product p JOIN ProductColor pc ON pc.product = p "
                + "WHERE pc.colorFamily = :colorFamily AND p.status = 'ACTIVE' AND "
                + STOREFRONT_BROWSABLE_LISTING,
        countQuery = "SELECT COUNT(DISTINCT p) FROM Product p JOIN ProductColor pc ON pc.product = p "
                + "WHERE pc.colorFamily = :colorFamily AND p.status = 'ACTIVE' AND "
                + STOREFRONT_BROWSABLE_LISTING
    )
    Page<Product> findByColorFamily(@Param("colorFamily") ColorFamily colorFamily, Pageable pageable);
}
