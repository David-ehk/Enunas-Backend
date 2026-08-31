package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /brand/orders (and every other /brand/orders/** endpoint) used to return
 * OrderService.toDto(order) — the FULL order, every brand's line items, shipping revenue,
 * customer PII, and returns — to ANY brand with a single item on a multi-brand order. Two brands
 * on one order, each queried with its own token, received byte-identical payloads: Brand B could
 * read Brand A's unit prices, volumes, shipping revenue and vice versa.
 *
 * Fixed by scoping every /brand/orders/** response to the querying brand's own items/shipping
 * snapshot/return, with the order total recomputed from just those (OrderService.toBrandScopedDto).
 */
class BrandOrderIsolationIntegrationTest extends AbstractDiscountIntegrationTest {

    /** No SMTP in the test env; confirmShipment's mail is not best-effort and would 500 the ship. */
    @MockitoBean private EmailService emailService;

    private record TwoBrandOrder(long orderId, String productA, String productB,
                                  String tokenA, String tokenB, String adminToken) {}

    private TwoBrandOrder placeTwoBrandOrder() {
        BrandPartner a = seedBrand("Alpha", "alpha", "0.15").brand();
        BrandPartner b = seedBrand("Beta", "beta", "0.15").brand();
        seedCustomer();
        seedAdmin();
        long listingA = seedListing(a, a.getUser(), "89.95", 5);
        long listingB = seedListing(b, b.getUser(), "29.95", 5);
        String productA = productName(listingA);
        String productB = productName(listingB);

        String customerToken = login("customer@it.local", "Customer123!");
        String tokenA = login("alpha@it.local", "Brand123!");
        String tokenB = login("beta@it.local", "Brand123!");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        return new TwoBrandOrder(orderId, productA, productB, tokenA, tokenB, adminToken);
    }

    private String productName(long listingId) {
        return jdbc.queryForObject(
                "SELECT p.name FROM products p JOIN listings l ON l.product_id = p.id WHERE l.id = ?",
                String.class, listingId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> brandOrdersContent(String token) {
        ResponseEntity<Map> resp = rest.exchange("/brand/orders", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) resp.getBody().get("content");
    }

    @Test
    void brandA_seesOnlyOwnItemAndTotal_notBrandBs() {
        TwoBrandOrder o = placeTwoBrandOrder();

        List<Map<String, Object>> content = brandOrdersContent(o.tokenA());
        assertThat(content).hasSize(1);
        Map<String, Object> order = content.get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("productName")).isEqualTo(o.productA());
        assertThat(items).extracting(i -> i.get("productName")).doesNotContain(o.productB());

        // Total must reflect only Brand A's item (89.95) + Brand A's own shipping (4.99 default rate).
        assertThat(new BigDecimal(order.get("total").toString())).isEqualByComparingTo("94.94");
        // Defense in depth: Brand B's product name must not appear anywhere in the payload at all.
        assertThat(order.toString()).doesNotContain(o.productB());
    }

    @Test
    void brandB_seesOnlyOwnItemAndTotal_notBrandAs() {
        TwoBrandOrder o = placeTwoBrandOrder();

        List<Map<String, Object>> content = brandOrdersContent(o.tokenB());
        assertThat(content).hasSize(1);
        Map<String, Object> order = content.get(0);
        List<Map<String, Object>> items = (List<Map<String, Object>>) order.get("items");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("productName")).isEqualTo(o.productB());
        assertThat(items).extracting(i -> i.get("productName")).doesNotContain(o.productA());

        assertThat(new BigDecimal(order.get("total").toString())).isEqualByComparingTo("34.94");
        assertThat(order.toString()).doesNotContain(o.productA());
    }

    @Test
    void confirmShipment_responseIsAlsoScopedToOwnBrand() {
        TwoBrandOrder o = placeTwoBrandOrder();

        ResponseEntity<Map> resp = rest.exchange("/brand/orders/" + o.orderId() + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "TRACK-1"), auth(o.tokenA())), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("productName")).isEqualTo(o.productA());
        assertThat(resp.getBody().toString()).doesNotContain(o.productB());
    }
}
