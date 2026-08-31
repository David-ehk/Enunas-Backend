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

    // ===== Public storefront browse (PLP) — every method below requires BOTH ACTIVE moderation
    // status AND at least one currently-active listing (active=true, within its availability
    // window). A product whose only listing(s) a brand deactivated must disappear from every one
    // of these, not just render with a null/zero price — the checkout guard
    // (OrderService.resolveAndValidateListings) already rejects an inactive listing at purchase
    // time; this is the matching browse-time gate so the frontend never offers it in the first
    // place. See ProductService.assertBrowsable for the identical PDP-side (single-product) gate. =====

    @Query("""
            SELECT p FROM Product p
            WHERE p.status = :status
              AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = p AND l.active = true
                          AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP)
                          AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))
            """)
    Page<Product> findByStatus(@Param("status") ProductStatus status, Pageable pageable);

    @Query("""
            SELECT p FROM Product p
            WHERE p.category = :category AND p.status = 'ACTIVE'
              AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = p AND l.active = true
                          AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP)
                          AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))
            """)
    Page<Product> findByCategory(@Param("category") ProductCategory category, Pageable pageable);

    @Query("""
            SELECT p FROM Product p
            WHERE p.status = 'ACTIVE'
              AND (LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(p.brand.brandName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                   OR LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = p AND l.active = true
                          AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP)
                          AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))
            """)
    Page<Product> search(@Param("keyword") String keyword, Pageable pageable);

    @Query(
        value = "SELECT DISTINCT pc.product FROM ProductColor pc " +
                "WHERE pc.colorFamily = :colorFamily AND pc.product.status = 'ACTIVE' " +
                "AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = pc.product AND l.active = true " +
                "AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP) " +
                "AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))",
        countQuery = "SELECT COUNT(DISTINCT pc.product) FROM ProductColor pc " +
                     "WHERE pc.colorFamily = :colorFamily AND pc.product.status = 'ACTIVE' " +
                     "AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = pc.product AND l.active = true " +
                     "AND (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP) " +
                     "AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP))"
    )
    Page<Product> findByColorFamily(@Param("colorFamily") ColorFamily colorFamily, Pageable pageable);
}
