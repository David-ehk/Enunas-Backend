package com.enunas.backend.customer.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /customer/me's totalOrders/totalSpent used to be a denormalized Customer entity pair that
 * was never wired up to update (see Customer.java history) — always 0/0.00 regardless of real
 * orders. Now computed on read from Order rows; these tests pin the status semantics: everything
 * except PENDING (never paid) and CANCELLED counts, same convention as
 * OrderItemRepository.findVat22fLineItems.
 */
class CustomerOrderStatsIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void noOrders_returnsZero() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map me = rest.exchange("/customer/me", HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class).getBody();

        assertThat(me.get("totalOrders")).isEqualTo(0);
        assertThat(new BigDecimal(me.get("totalSpent").toString())).isEqualByComparingTo("0.00");
    }

    @Test
    void pendingOrder_doesNotCount() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        String customerToken = login("customer@it.local", "Customer123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);

        postOrder(customerToken, null, List.of(item(listing, 1))); // left PENDING, never confirmed

        Map me = rest.exchange("/customer/me", HttpMethod.GET, new HttpEntity<>(auth(customerToken)), Map.class).getBody();

        assertThat(me.get("totalOrders")).isEqualTo(0);
        assertThat(new BigDecimal(me.get("totalSpent").toString())).isEqualByComparingTo("0.00");
    }

    @Test
    void paidOrder_countsTowardTotals() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        String customerToken = login("customer@it.local", "Customer123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        Map me = rest.exchange("/customer/me", HttpMethod.GET, new HttpEntity<>(auth(customerToken)), Map.class).getBody();

        assertThat(me.get("totalOrders")).isEqualTo(1);
        assertThat(new BigDecimal(me.get("totalSpent").toString())).isEqualByComparingTo(
                new BigDecimal(order.getBody().get("total").toString()));
    }

    @Test
    void cancelledOrder_doesNotCount() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        var admin = seedAdmin();
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        // Cancel only reaches from PENDING (see OrderStatus) — no confirmPaid before this.
        rest.exchange("/admin/orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST"), auth(adminToken)), Map.class);

        Map me = rest.exchange("/customer/me", HttpMethod.GET, new HttpEntity<>(auth(customerToken)), Map.class).getBody();

        assertThat(me.get("totalOrders")).isEqualTo(0);
        assertThat(new BigDecimal(me.get("totalSpent").toString())).isEqualByComparingTo("0.00");
    }

    @Test
    void multiplePaidOrders_sumCorrectly() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        String customerToken = login("customer@it.local", "Customer123!");
        long listingA = seedListing(brand.brand(), brand.user(), "50.00", 5);
        long listingB = seedListing(brand.brand(), brand.user(), "30.00", 5);

        ResponseEntity<Map> orderA = postOrder(customerToken, null, List.of(item(listingA, 1)));
        confirmPaid(((Number) orderA.getBody().get("id")).longValue());
        ResponseEntity<Map> orderB = postOrder(customerToken, null, List.of(item(listingB, 1)));
        confirmPaid(((Number) orderB.getBody().get("id")).longValue());

        BigDecimal expectedTotal = new BigDecimal(orderA.getBody().get("total").toString())
                .add(new BigDecimal(orderB.getBody().get("total").toString()));

        Map me = rest.exchange("/customer/me", HttpMethod.GET, new HttpEntity<>(auth(customerToken)), Map.class).getBody();

        assertThat(me.get("totalOrders")).isEqualTo(2);
        assertThat(new BigDecimal(me.get("totalSpent").toString())).isEqualByComparingTo(expectedTotal);
    }
}
