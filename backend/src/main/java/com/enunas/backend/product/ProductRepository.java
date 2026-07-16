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

    Page<Product> findByCategory(ProductCategory category, Pageable pageable);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryAndStatus(ProductCategory category, ProductStatus status, Pageable pageable);

    boolean existsByCollectionName(String collectionName);

    @Query("SELECT p FROM Product p WHERE " +
           "LOWER(p.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.brand.brandName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(p.description) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<Product> search(@Param("keyword") String keyword, Pageable pageable);

    @Query(
        value = "SELECT DISTINCT pc.product FROM ProductColor pc " +
                "WHERE pc.colorFamily = :colorFamily AND pc.product.status = 'ACTIVE'",
        countQuery = "SELECT COUNT(DISTINCT pc.product) FROM ProductColor pc " +
                     "WHERE pc.colorFamily = :colorFamily AND pc.product.status = 'ACTIVE'"
    )
    Page<Product> findByColorFamily(@Param("colorFamily") ColorFamily colorFamily, Pageable pageable);
}
