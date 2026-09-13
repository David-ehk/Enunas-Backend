package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.ProductImageRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order_items.variant_snapshot_image_key — colour-cover -> shared-cover -> null, never a
 * different colourway's photo. See docs/superpowers/specs/2026-09-10-order-item-image-snapshot-design.md.
 */
class OrderItemImageSnapshotIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductImageRepository imageRepository;

    private record TwoColourFixture(Product product, ProductColor black, ProductColor white,
                                     long blackListingId, long whiteListingId) {}

    /** Builds Product -> {BLACK, WHITE} ProductColors -> one variant each -> one listing each. */
    private TwoColourFixture seedTwoColourProduct(BrandPartner brand, User creator, String priceEuros, int stock) {
        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 6);
        Product p = productRepository.save(Product.builder()
                .name(name).slug(name.toLowerCase()).brand(brand).creator(creator).build());
        ProductColor black = productColorRepository.save(ProductColor.builder()
                .sku(sku()).color("Black").colorFamily(ColorFamily.BLACK).product(p).build());
        ProductColor white = productColorRepository.save(ProductColor.builder()
                .sku(sku()).color("White").colorFamily(ColorFamily.WHITE).product(p).build());
        ProductVariant blackVariant = productVariantRepository.save(ProductVariant.builder()
                .productColor(black).size("M").stockQuantity(stock).product(p).build());
        ProductVariant whiteVariant = productVariantRepository.save(ProductVariant.builder()
                .productColor(white).size("M").stockQuantity(stock).product(p).build());
        long blackListingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(blackVariant).price(new BigDecimal(priceEuros))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        long whiteListingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(whiteVariant).price(new BigDecimal(priceEuros))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        return new TwoColourFixture(p, black, white, blackListingId, whiteListingId);
    }

    private String sku() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private ProductImage seedImage(Product product, ProductColor color, boolean primary, int displayOrder) {
        return imageRepository.save(ProductImage.builder()
                .product(product).productColor(color)
                .storageKey("products/" + product.getId() + "/images/" + UUID.randomUUID() + ".jpg")
                .primary(primary).displayOrder(displayOrder).build());
    }

    private String snapshotKeyForFirstItem(long orderId) {
        return jdbc.queryForObject(
                "SELECT variant_snapshot_image_key FROM order_items WHERE order_id = ? ORDER BY id LIMIT 1",
                String.class, orderId);
    }

    @Test
    void order_colourWithOwnImages_snapshotsThatColoursPrimaryOverLowerDisplayOrder() {
        BrandPartner brand = seedBrand("Acme1", "acme1", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        ProductImage blackNonPrimary = seedImage(fx.product(), fx.black(), false, 0);
        ProductImage blackPrimary = seedImage(fx.product(), fx.black(), true, 5);
        seedImage(fx.product(), fx.white(), true, 0); // WHITE's own cover — must NOT leak into BLACK's order
        seedImage(fx.product(), null, true, 0);        // a shared primary — must NOT win over BLACK's own

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.blackListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isEqualTo(blackPrimary.getStorageKey());
        assertThat(snapshotKeyForFirstItem(orderId)).isNotEqualTo(blackNonPrimary.getStorageKey());
    }

    @Test
    void order_noPrimaryInGroup_tiebreaksOnLowestDisplayOrderThenId() {
        // V32's partial unique indexes allow at most one primary per colour group, so the primary
        // tier alone never exercises the displayOrder/id tiebreak — only a group with ZERO
        // primaries reaches it (e.g. a brand uploads several photos and marks none as the cover,
        // which nothing forbids). Seeded out of id/displayOrder order on purpose: the FIRST-seeded
        // image (lower id) gets the HIGHER displayOrder, so a broken or reversed
        // thenComparingInt(displayOrder) would incorrectly pick it via the id tiebreak instead.
        BrandPartner brand = seedBrand("Acme6", "acme6", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        ProductImage lowerIdHigherDisplayOrder = seedImage(fx.product(), fx.black(), false, 5);
        ProductImage higherIdLowerDisplayOrder = seedImage(fx.product(), fx.black(), false, 0);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.blackListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isEqualTo(higherIdLowerDisplayOrder.getStorageKey());
        assertThat(snapshotKeyForFirstItem(orderId)).isNotEqualTo(lowerIdHigherDisplayOrder.getStorageKey());
    }

    @Test
    void order_colourWithNoOwnImage_fallsBackToSharedCover() {
        BrandPartner brand = seedBrand("Acme2", "acme2", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        seedImage(fx.product(), fx.black(), true, 0); // BLACK has its own — irrelevant to this test
        ProductImage shared = seedImage(fx.product(), null, true, 0); // WHITE has none; shared exists

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.whiteListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isEqualTo(shared.getStorageKey());
    }

    @Test
    void order_assumptionViolated_noOwnImageAndNoSharedImage_snapshotsNullNeverAnotherColour() {
        BrandPartner brand = seedBrand("Acme3", "acme3", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        // Only BLACK has an image; no shared image exists at all — the invariant this feature
        // assumes (spec §3) is violated on purpose here to prove the code degrades safely.
        seedImage(fx.product(), fx.black(), true, 0);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.whiteListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isNull();
    }

    @Test
    void order_productWithNoImagesAtAll_snapshotsNull() {
        BrandPartner brand = seedBrand("Acme4", "acme4", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isNull();

        // Spec §6 also names the HTTP-level shape, not just the persisted column: GET must still
        // return 200 with imageUrl == null, never an error from resolving a null key.
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> get = rest.exchange("/orders/" + orderId, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth(customerToken)), Map.class);
        assertThat(get.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get.getBody().get("items");
        assertThat(items.get(0).get("imageUrl")).isNull();
    }

    @Test
    void getOrder_returnsResolvedImageUrlForTheOrderedColour() {
        BrandPartner brand = seedBrand("Acme5", "acme5", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        ProductImage blackCover = seedImage(fx.product(), fx.black(), true, 0);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.blackListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> get = rest.exchange("/orders/" + orderId, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth(customerToken)), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get.getBody().get("items");
        assertThat(items.get(0).get("imageUrl")).isEqualTo("http://localhost:1/cdn/" + blackCover.getStorageKey());
    }
}
