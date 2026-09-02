package com.enunas.backend.product.integration;

import com.enunas.backend.admin.AdminService;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.exception.ErrorCode;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductStatus;
import com.enunas.backend.product.dto.UpdateProductDto;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting and archiving a product, end to end.
 *
 * <p>Both exits were closed for a brand partner. A product that had ever had a variant could not be
 * deleted — {@code product_colors} rows are created with the first variant, are not covered by any
 * cascade on {@link Product}, and are never deleted by anything, so the delete aborted on their
 * foreign key as an opaque "Data integrity violation" 409 with nothing left for the brand to remove.
 * The fallback that same 409 recommends — set the status to ARCHIVED — was rejected by
 * {@code UpdateProductDto} as an unknown property, because it had no status field at all. There were
 * no tests over {@code deleteProduct} on either route, which is why both shipped.
 */
class ProductDeletionIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private AdminService adminService;

    // ===== The delete route =====

    @Test
    void brandDeletesProductWithVariants_succeedsAndLeavesNoColourRows() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 3);
        String token = login("acme@it.local", "Brand123!");

        assertThat(colourCount(productId)).isEqualTo(3);

        ResponseEntity<Map> resp = delete("/products/delete/" + productId, token);

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productCount(productId)).isZero();
        assertThat(colourCount(productId)).isZero();
        assertThat(variantCount(productId)).isZero();
    }

    /**
     * The exact production sequence: the brand strips the product by hand first, then retries the
     * delete. Removing the variants is what leaves the orphan colour behind, so this is the path
     * that produced "Data integrity violation" on a product with nothing left on it.
     */
    @Test
    void brandDeletesProductAfterRemovingItsVariantsByHand_succeeds() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 3);
        String token = login("acme@it.local", "Brand123!");

        for (Long variantId : variantIds(productId)) {
            ResponseEntity<Map> variantResp =
                    delete("/products/" + productId + "/variants/" + variantId, token);
            assertThat(variantResp.getStatusCode().is2xxSuccessful())
                    .as("variant %s: %s", variantId, variantResp.getBody()).isTrue();
        }
        assertThat(variantCount(productId)).isZero();
        assertThat(colourCount(productId)).as("deleting variants leaves the colours behind").isEqualTo(3);

        ResponseEntity<Map> resp = delete("/products/delete/" + productId, token);

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productCount(productId)).isZero();
        assertThat(colourCount(productId)).isZero();
    }

    /**
     * {@code product_complete_the_look} has a foreign key to {@code products} on both columns, but
     * Hibernate clears only the owning side. A row where another product names this one aborted the
     * delete identically.
     */
    @Test
    void brandDeletesProductReferencedByAnotherProductsCompleteTheLook_succeeds() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long targetId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        long referrerId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        linkCompleteTheLook(referrerId, targetId);
        String token = login("acme@it.local", "Brand123!");

        assertThat(completeTheLookRowCount(targetId)).isEqualTo(1);

        ResponseEntity<Map> resp = delete("/products/delete/" + targetId, token);

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productCount(targetId)).isZero();
        assertThat(completeTheLookRowCount(targetId)).isZero();
        assertThat(productCount(referrerId)).as("the referring product survives").isEqualTo(1);
    }

    @Test
    void adminDeletesProductWithVariants_succeeds() {
        seedAdmin();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 2);
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> resp = delete("/admin/products/" + productId, adminToken);

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(productCount(productId)).isZero();
        assertThat(colourCount(productId)).isZero();
    }

    // ===== The guards that must keep refusing =====

    @Test
    void brandDeletesProductWithListings_isRefusedAndNamesListings() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listingId = seedListing(brand.brand(), brand.user(), "100.00", 5);
        long productId = productIdOfListing(listingId);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = delete("/products/delete/" + productId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The code is the contract the frontend branches on; the message is free to be reworded.
        assertThat(resp.getBody().get("code")).isEqualTo(ErrorCode.PRODUCT_HAS_LISTINGS.name());
        assertThat((String) resp.getBody().get("message"))
                .contains("still has listings")
                .contains("ARCHIVED");
        assertThat(productCount(productId)).isEqualTo(1);
    }

    @Test
    void brandDeletesOrderedProduct_isRefusedAndNamesOrderHistory() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listingId = seedListing(brand.brand(), brand.user(), "100.00", 5);
        long productId = productIdOfListing(listingId);
        ResponseEntity<Map> orderResp =
                postOrder(login("customer@it.local", "Customer123!"), null, List.of(item(listingId, 1)));
        assertThat(orderResp.getStatusCode().is2xxSuccessful())
                .as("order: %s", orderResp.getBody()).isTrue();

        // Clear the listings so the order-history guard is the one under test, not the listing guard.
        jdbc.update("DELETE FROM listings WHERE product_id = ?", productId);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = delete("/products/delete/" + productId, token);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // PRODUCT_HAS_ORDERS is the one a client must never offer "remove the listings" for: no
        // amount of removing anything makes this product deletable.
        assertThat(resp.getBody().get("code")).isEqualTo(ErrorCode.PRODUCT_HAS_ORDERS.name());
        assertThat((String) resp.getBody().get("message"))
                .contains("already been ordered")
                .contains("ARCHIVED");
        assertThat(productCount(productId)).isEqualTo(1);
    }

    // ===== The archive route the delete refusal points at =====

    @Test
    void brandArchivesProduct_isAcceptedAndTakesItOffTheStorefront() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listingId = seedListing(brand.brand(), brand.user(), "100.00", 5);
        long productId = productIdOfListing(listingId);
        String token = login("acme@it.local", "Brand123!");

        assertThat(rest.getForEntity("/products/" + productId, Map.class).getStatusCode())
                .as("browsable before archiving").isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> resp = put("/products/update/" + productId, token, Map.of("status", "ARCHIVED"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo(ProductStatus.ARCHIVED.name());
        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ARCHIVED.name());
        assertThat(rest.getForEntity("/products/" + productId, Map.class).getStatusCode())
                .as("anonymous storefront read after archiving").isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void brandArchivesProductAlongsideOtherFields_isAccepted() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = put("/products/update/" + productId, token,
                Map.of("name", "Renamed Hoodie", "status", "ARCHIVED"));

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ARCHIVED.name());
        assertThat(resp.getBody().get("name")).isEqualTo("Renamed Hoodie");
    }

    @Test
    void brandSetsModerationStatus_isRejected() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = put("/products/update/" + productId, token, Map.of("status", "SUSPENDED"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // Specifically NOT the "Unknown field: status" 400 the DTO used to answer with — that would
        // make this test pass against exactly the build it exists to catch.
        assertThat((String) resp.getBody().get("message"))
                .doesNotContain("Unknown field")
                .contains("admin moderation");
        // An error with nothing to branch on carries no code key at all — codes are additive, so
        // every response that never had one keeps the exact body it had.
        assertThat(resp.getBody()).doesNotContainKey("code");
        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ACTIVE.name());
    }

    @Test
    void brandCannotReactivateProductAnAdminSuspended() {
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        jdbc.update("UPDATE products SET status = 'SUSPENDED' WHERE id = ?", productId);
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = put("/products/update/" + productId, token, Map.of("status", "ACTIVE"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(productId)).isEqualTo(ProductStatus.SUSPENDED.name());
    }

    /**
     * The admin route is the moderation authority and keeps the override the brand route refuses.
     * Driven through the service rather than over HTTP because TestRestTemplate cannot issue PATCH
     * without httpclient5 on the test classpath, and {@code PATCH /admin/products/{id}} is the only
     * shape this endpoint has.
     */
    @Test
    void adminSetsModerationStatusTheBrandCannot() {
        User admin = seedAdmin();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long productId = seedProductWithVariants(brand.brand(), brand.user(), 1);
        jdbc.update("UPDATE products SET status = 'SUSPENDED' WHERE id = ?", productId);

        UpdateProductDto dto = new UpdateProductDto();
        dto.setStatus(ProductStatus.ACTIVE);
        asAdmin(admin, () -> adminService.updateProduct(productId, dto));

        assertThat(statusOf(productId)).isEqualTo(ProductStatus.ACTIVE.name());
    }

    /** AdminService is class-level @PreAuthorize("hasRole('ADMIN')"), so it needs a real principal. */
    private void asAdmin(User admin, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ===== Helpers =====

    /** A product with {@code colours} variants, each its own colour — and deliberately no listing. */
    private long seedProductWithVariants(BrandPartner brand, User creator, int colours) {
        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 8);
        Product product = productRepository.save(Product.builder()
                .name(name)
                .slug(name.toLowerCase())
                .brand(brand)
                .creator(creator)
                .build());

        for (int i = 0; i < colours; i++) {
            ProductColor colour = productColorRepository.save(ProductColor.builder()
                    .sku(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
                    .color("Colour-" + i)
                    .colorFamily(ColorFamily.BLACK)
                    .product(product)
                    .build());
            productVariantRepository.save(ProductVariant.builder()
                    .productColor(colour)
                    .size("M")
                    .stockQuantity(10)
                    .product(product)
                    .build());
        }
        return product.getId();
    }

    private void linkCompleteTheLook(long referrerId, long relatedId) {
        jdbc.update("INSERT INTO product_complete_the_look (product_id, related_product_id) VALUES (?, ?)",
                referrerId, relatedId);
    }

    private long productIdOfListing(long listingId) {
        return jdbc.queryForObject("SELECT product_id FROM listings WHERE id = ?", Long.class, listingId);
    }

    private List<Long> variantIds(long productId) {
        return jdbc.queryForList("SELECT id FROM product_variants WHERE product_id = ? ORDER BY id",
                Long.class, productId);
    }

    private String statusOf(long productId) {
        return jdbc.queryForObject("SELECT status FROM products WHERE id = ?", String.class, productId);
    }

    private int productCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE id = ?", Integer.class, productId);
    }

    private int colourCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_colors WHERE product_id = ?",
                Integer.class, productId);
    }

    private int variantCount(long productId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_variants WHERE product_id = ?",
                Integer.class, productId);
    }

    private int completeTheLookRowCount(long productId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_complete_the_look WHERE product_id = ? OR related_product_id = ?",
                Integer.class, productId, productId);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> delete(String path, String token) {
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(auth(token)), Map.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> put(String path, String token, Map<String, Object> body) {
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, auth(token)), Map.class);
    }
}
