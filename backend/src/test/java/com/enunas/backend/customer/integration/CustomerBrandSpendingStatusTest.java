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
 * GET /admin/customers/{id}/brand-spending and GET /customer/me are two views of the same money, so
 * they have to agree on which orders count. They didn't: the profile totals use "everything except
 * PENDING and CANCELLED", while brand spending filtered on a hand-written list of paid statuses
 * that had drifted — PARTIALLY_SHIPPED was missing until recently, and SHIPPING_PROBLEM,
 * AWAITING_ADMIN and MANUAL_REVIEW still were. All of those are reachable only from PAID or
 * PARTIALLY_SHIPPED, so in every one of them the customer's money has already been taken, and the
 * breakdown quietly totalled less than the profile figure it sits next to.
 *
 * <p>Both now read {@code OrderStatus.PAID_ORDER_STATUSES}. These tests pin the agreement on the
 * statuses that were actually wrong, rather than the constant's definition, which is true by
 * construction.
 */
class CustomerBrandSpendingStatusTest extends AbstractDiscountIntegrationTest {

    @SuppressWarnings("unchecked")
    private BigDecimal brandSpendingTotal(String adminToken, long customerId) {
        List<Map<String, Object>> rows = rest.exchange(
                "/admin/customers/" + customerId + "/brand-spending", HttpMethod.GET,
                new HttpEntity<>(auth(adminToken)), List.class).getBody();
        return rows.stream()
                .map(r -> new BigDecimal(r.get("totalSpent").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @SuppressWarnings("unchecked")
    private BigDecimal profileTotalSpent(String customerToken) {
        Map<String, Object> me = rest.exchange("/customer/me", HttpMethod.GET,
                new HttpEntity<>(auth(customerToken)), Map.class).getBody();
        return new BigDecimal(me.get("totalSpent").toString());
    }

    private long customerId() {
        return jdbc.queryForObject("SELECT id FROM customers", Long.class);
    }

    /**
     * An order an admin escalated to SHIPPING_PROBLEM is paid-for merchandise with a logistics
     * problem attached — the money is ours either way. It counted toward the profile total and was
     * absent from the brand breakdown.
     */
    @SuppressWarnings("unchecked")
    @Test
    void escalatedOrder_countsInBrandSpendingJustAsItDoesInProfileTotals() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        seedAdmin();
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 2)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        rest.exchange("/admin/orders/" + orderId + "/status?status=SHIPPING_PROBLEM",
                HttpMethod.PATCH, new HttpEntity<>(auth(adminToken)), Map.class);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("SHIPPING_PROBLEM");

        // 2 x 50.00 of merchandise. Shipping is not part of brand spending, so compare the line
        // totals against the order's own subtotal rather than its grand total.
        BigDecimal subtotal = new BigDecimal(order.getBody().get("subtotal").toString());
        assertThat(brandSpendingTotal(adminToken, customerId())).isEqualByComparingTo(subtotal);
        assertThat(profileTotalSpent(customerToken)).isGreaterThanOrEqualTo(subtotal);
    }

    /** PARTIALLY_SHIPPED — added to the enum in V27, added to the spending list only later. */
    @SuppressWarnings("unchecked")
    @Test
    void partiallyShippedOrder_countsInBrandSpending() {
        var alpha = seedBrand("Alpha", "alpha", "0.15");
        var beta = seedBrand("Beta", "beta", "0.15");
        seedCustomer();
        String customerToken = login("customer@it.local", "Customer123!");
        String alphaToken = login("alpha@it.local", "Brand123!");
        seedAdmin();
        String adminToken = login("admin@it.local", "Admin123!");
        long listingA = seedListing(alpha.brand(), alpha.user(), "40.00", 5);
        long listingB = seedListing(beta.brand(), beta.user(), "60.00", 5);

        ResponseEntity<Map> order = postOrder(customerToken, null,
                List.of(item(listingA, 1), item(listingB, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "TRACK-A"), auth(alphaToken)),
                Map.class);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("PARTIALLY_SHIPPED");

        // Both brands' items are on a paid order, so both appear — not just the one that shipped.
        assertThat(brandSpendingTotal(adminToken, customerId()))
                .isEqualByComparingTo(new BigDecimal(order.getBody().get("subtotal").toString()));
    }

    /** The unpaid end of the rule still holds: a PENDING order is money nobody has taken. */
    @SuppressWarnings("unchecked")
    @Test
    void pendingOrder_countsInNeitherView() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        seedAdmin();
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);

        postOrder(customerToken, null, List.of(item(listing, 1))); // never confirmed

        assertThat(brandSpendingTotal(adminToken, customerId())).isEqualByComparingTo("0.00");
        assertThat(profileTotalSpent(customerToken)).isEqualByComparingTo("0.00");
    }
}
