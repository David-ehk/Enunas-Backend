package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.order.OrderExpiryService;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

/**
 * Phase 3: the 14-day Widerruf window (anchored on {@code Order.deliveredAt}), discount-usage
 * release on cancellation/full-refund, and the refund-confirmation email. Each is independent of
 * the multi-brand split covered by {@link MultiBrandReturnTest}, so it gets its own file.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class ReturnLifecyclePhase3Test extends AbstractDiscountIntegrationTest {

    @MockitoBean private EmailService emailService;
    @Autowired private OrderExpiryService orderExpiryService;

    private BrandFixture brandWithReturnWarehouse(String name, String slug) {
        BrandFixture f = seedBrand(name, slug, "0.18");
        BrandPartner b = f.brand();
        b.setLegalName(name + " GmbH");
        b.setAddressStreet("Steuerweg 1");
        b.setAddressPostalCode("10115");
        b.setAddressCity("Berlin");
        b.setAddressCountry("DE");
        brandPartnerRepository.save(b);
        return f;
    }

    /** See MultiBrandReturnTest.deliver — same fix, same reasoning (per-brand shipment: DELIVERED
     *  is only reachable once every brand has shipped, so admin's bulk SHIPPED override completes
     *  any brand this fixture didn't explicitly ship). */
    private void deliver(long orderId, String shippingBrandToken, String adminToken) {
        confirmPaid(orderId);
        ResponseEntity<Map> shipped = rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "TRACK-1"),
                        auth(shippingBrandToken)), Map.class);
        assertThat(shipped.getStatusCode().is2xxSuccessful()).as("ship: %s", shipped.getBody()).isTrue();
        ResponseEntity<Map> allShipped = rest.exchange("/admin/orders/" + orderId + "/status?status=SHIPPED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(allShipped.getStatusCode().is2xxSuccessful()).as("bulk-ship: %s", allShipped.getBody()).isTrue();
        ResponseEntity<Map> delivered = rest.exchange("/admin/orders/" + orderId + "/status?status=DELIVERED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(delivered.getStatusCode().is2xxSuccessful()).as("deliver: %s", delivered.getBody()).isTrue();
    }

    private ResponseEntity<Map> requestReturn(String customerToken, long orderId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "WRONG_SIZE");
        body.put("description", "Passt nicht");
        return rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(customerToken)), Map.class);
    }

    private void backdateDelivery(long orderId, int daysAgo) {
        jdbc.update("UPDATE orders SET delivered_at = now() - (? || ' days')::interval WHERE id = ?",
                daysAgo, orderId);
    }

    // ===== 14-day Widerruf =====

    @Test
    @DisplayName("A return requested within 14 days of delivery is accepted")
    void returnWithinWindowIsAccepted() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        backdateDelivery(orderId, 10); // within the 14-day window

        assertThat(requestReturn(customerToken, orderId).getStatusCode().is2xxSuccessful()).isTrue();
    }

    @Test
    @DisplayName("A return requested after 14 days of delivery is rejected")
    void returnAfterWindowIsRejected() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        backdateDelivery(orderId, 15); // past the 14-day window

        assertThat(requestReturn(customerToken, orderId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("An admin goodwill return bypasses the expired 14-day window")
    void adminGoodwillReturnBypassesTheWindow() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        backdateDelivery(orderId, 30); // well past the window

        assertThat(requestReturn(customerToken, orderId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<String, Object> body = new HashMap<>();
        body.put("reason", "OTHER");
        body.put("description", "Goodwill — approved by support");
        ResponseEntity<Map> goodwill = rest.exchange("/admin/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
        assertThat(goodwill.getStatusCode().is2xxSuccessful()).as("goodwill: %s", goodwill.getBody()).isTrue();
    }

    // ===== Discount usage release =====

    @Test
    @DisplayName("Cancelling a PENDING order with a discount code releases its usage")
    void cancellingPendingOrderReleasesDiscountUsage() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "119.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        createAdminDiscount(adminToken, Map.of("code", "REL10", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL10", List.of(item(listingA, 1))));
        assertThat(usedCount("REL10")).isEqualTo(1);

        Map<String, Object> cancelBody = Map.of("reason", "CUSTOMER_REQUEST", "note", "changed mind");
        ResponseEntity<Map> cancelled = rest.exchange("/admin/orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(cancelBody, auth(adminToken)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(usedCount("REL10")).as("usage released on cancel").isEqualTo(0);
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    @DisplayName("Fully refunding an order with a discount code releases its usage")
    void fullyRefundingOrderReleasesDiscountUsage() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "119.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");
        createAdminDiscount(adminToken, Map.of("code", "REL20", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL20", List.of(item(listingA, 1))));
        assertThat(usedCount("REL20")).isEqualTo(1);

        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId);
        String returnNumber = (String) requested.getBody().get("returnNumber");

        rest.exchange("/admin/returns/" + returnNumber + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/returns/" + returnNumber + "/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        ResponseEntity<Map> refunded = rest.exchange("/admin/returns/" + returnNumber + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(refunded.getStatusCode().is2xxSuccessful()).as("refund: %s", refunded.getBody()).isTrue();

        assertThat(usedCount("REL20")).as("usage released once the whole order is refunded").isEqualTo(0);
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    @DisplayName("Refunding only one brand of a multi-brand order does NOT release the discount usage")
    void partialRefundDoesNotReleaseDiscountUsage() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithReturnWarehouse("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");
        createAdminDiscount(adminToken, Map.of("code", "REL30", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL30",
                List.of(item(listingA, 1), item(listingB, 1))));
        assertThat(usedCount("REL30")).isEqualTo(1);

        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId);
        List<Map<String, Object>> returns = (List<Map<String, Object>>) requested.getBody().get("returns");
        String returnA = (String) returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");

        rest.exchange("/admin/returns/" + returnA + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/returns/" + returnA + "/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/returns/" + returnA + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        assertThat(usedCount("REL30"))
                .as("Brand B's return is still open — the order-level discount usage must stay reserved")
                .isEqualTo(1);
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(false);
    }

    @Test
    @DisplayName("Refunding every brand of a multi-brand order releases discount usage exactly once")
    void refundingEveryBrandReleasesDiscountUsageExactlyOnce() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithReturnWarehouse("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");
        createAdminDiscount(adminToken, Map.of("code", "REL40", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL40",
                List.of(item(listingA, 1), item(listingB, 1))));
        assertThat(usedCount("REL40")).isEqualTo(1);

        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId);
        List<Map<String, Object>> returns = (List<Map<String, Object>>) requested.getBody().get("returns");
        String returnA = (String) returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");
        String returnB = (String) returns.stream()
                .filter(r -> "BrandB".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");

        for (String ret : List.of(returnA, returnB)) {
            rest.exchange("/admin/returns/" + ret + "/approve", HttpMethod.POST,
                    new HttpEntity<>(null, auth(adminToken)), Map.class);
            rest.exchange("/admin/returns/" + ret + "/receive", HttpMethod.POST,
                    new HttpEntity<>(null, auth(adminToken)), Map.class);
        }

        // Refund Brand A first — order not yet fully covered, usage must stay reserved.
        rest.exchange("/admin/returns/" + returnA + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(usedCount("REL40")).as("Brand B not yet refunded").isEqualTo(1);

        // Refund Brand B — now the whole order is covered; release must fire exactly once.
        ResponseEntity<Map> finalRefund = rest.exchange("/admin/returns/" + returnB + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(finalRefund.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(usedCount("REL40")).as("both brands refunded — usage released exactly once").isEqualTo(0);
        assertThat(orderRow(orderId).get("status")).isEqualTo("REFUNDED");
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    @DisplayName("Admin-cancelling a PAID order releases discount usage via the post-payment cancel path")
    void adminCancellingPaidOrderReleasesDiscountUsage() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "119.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        createAdminDiscount(adminToken, Map.of("code", "REL50", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL50", List.of(item(listingA, 1))));
        confirmPaid(orderId);
        assertThat(usedCount("REL50")).isEqualTo(1);

        ResponseEntity<Map> cancelled = rest.exchange(
                "/admin/orders/" + orderId + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();

        assertThat(usedCount("REL50")).as("released via the postPaymentCancel path").isEqualTo(0);
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    @DisplayName("Auto-expiry of a stale PENDING order releases its discount usage")
    void autoExpiryReleasesDiscountUsage() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "119.00", 5);

        String adminToken = login("admin@it.local", "Admin123!");
        String customerToken = login("customer@it.local", "Customer123!");
        createAdminDiscount(adminToken, Map.of("code", "REL60", "percent", 0.10));

        long orderId = orderId(postOrder(customerToken, "REL60", List.of(item(listingA, 1))));
        assertThat(usedCount("REL60")).isEqualTo(1);

        jdbc.update("UPDATE orders SET created_at = now() - interval '31 minutes' WHERE id = ?", orderId);
        orderExpiryService.cancelExpiredPendingOrders();

        assertThat(orderRow(orderId).get("status")).isEqualTo("CANCELLED");
        assertThat(usedCount("REL60")).as("released via OrderExpiryService").isEqualTo(0);
        assertThat(orderRow(orderId).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    @DisplayName("An admin goodwill return still rejects a duplicate return of an already-returned item")
    void adminGoodwillReturnStillRespectsThePerItemGuard() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        assertThat(requestReturn(customerToken, orderId).getStatusCode().is2xxSuccessful()).isTrue();

        // The item is already on a return — the admin goodwill path bypasses the 14-day window ONLY,
        // not the "already returned" guard, so a second request for the SAME item must still fail.
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "OTHER");
        body.put("description", "Goodwill retry");
        ResponseEntity<Map> retry = rest.exchange("/admin/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ===== Refund confirmation email =====

    @Test
    @DisplayName("A completed refund sends a confirmation email to the customer")
    void refundSendsConfirmationEmail() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId);
        String returnNumber = (String) requested.getBody().get("returnNumber");

        rest.exchange("/admin/returns/" + returnNumber + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/returns/" + returnNumber + "/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/returns/" + returnNumber + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        // approveReturn ALSO emails the customer (ReturnApprovedEmailListener) — distinguish the
        // refund-confirmation email by its distinct subject, not just the shared return number.
        verify(emailService).sendPlainTextEmail(
                org.mockito.ArgumentMatchers.eq("customer@it.local"),
                contains("Rückerstattung"),
                contains(returnNumber));
    }
}
