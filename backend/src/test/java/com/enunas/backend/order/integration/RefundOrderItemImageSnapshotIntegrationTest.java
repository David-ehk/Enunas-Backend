package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.ProductImageRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefundPersistenceHelper.persist builds its OrderResponseDto via its OWN injected
 * MediaUrlResolver field — same singleton bean as OrderService's, but a genuinely separate piece
 * of wiring (it is its own @Component so its own @Transactional proxy applies), which a missed
 * field or a missed argument at this call site would have made no OrderService test catch.
 * Exercised on its own rather than assumed correct because OrderService's tests pass.
 */
class RefundOrderItemImageSnapshotIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductImageRepository imageRepository;
    @MockitoBean private EmailService emailService;

    @Test
    void refundResponse_resolvesTheOrderedColoursImageUrl() {
        BrandPartner brand = seedBrand("AcmeR", "acmer", "0.15").brand();
        seedCustomer();
        seedAdmin();

        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 6);
        Product p = productRepository.save(Product.builder()
                .name(name).slug(name.toLowerCase()).brand(brand).creator(brand.getUser()).build());
        ProductColor black = productColorRepository.save(ProductColor.builder()
                .sku(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
                .color("Black").colorFamily(ColorFamily.BLACK).product(p).build());
        ProductVariant variant = productVariantRepository.save(ProductVariant.builder()
                .productColor(black).size("M").stockQuantity(5).product(p).build());
        long listingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(variant).price(new BigDecimal("50.00"))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        ProductImage cover = imageRepository.save(ProductImage.builder()
                .product(p).productColor(black)
                .storageKey("products/" + p.getId() + "/images/" + UUID.randomUUID() + ".jpg")
                .primary(true).displayOrder(0).build());

        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("acmer@it.local", "Brand123!");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/status?status=DELIVERED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        Map<String, Object> returnBody = new HashMap<>();
        returnBody.put("reason", "WRONG_SIZE");
        returnBody.put("description", "Passt nicht");
        rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(returnBody, auth(customerToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/return/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/return/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> refund = rest.exchange("/admin/orders/" + orderId + "/return/refund",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);

        assertThat(refund.getStatusCode().value()).as("body: %s", refund.getBody()).isEqualTo(200);
        assertThat(refund.getBody().get("status")).isEqualTo("REFUNDED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) refund.getBody().get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("imageUrl")).isEqualTo("http://localhost:1/cdn/" + cover.getStorageKey());
    }
}
