package com.enunas.backend.order.integration;

import com.enunas.backend.order.ReturnRefundService;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;

/**
 * Shared fixture for the partial-return/refund tests: one DELIVERED order with brand A a1 (EUR 119.00 x 2)
 * and a2 (EUR 59.50 x 1), and brand B b1 (EUR 100.00 x 1).
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class AbstractReturnRefundIntegrationTest extends AbstractItemCancellationIntegrationTest {

    record ReturnFixture(long orderId, long brandAId, long brandBId, long a1, long a2, long b1,
                         String adminToken, String customerToken, String brandAToken, String brandBToken) {}

    // Shipping sends mail inline.
    @MockitoBean EmailService emailService;
    @Autowired ReturnRefundService returnRefundService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    @AfterEach
    void restoreProvider() {
        doCallRealMethod().when(paymentProvider).refundPayment(any());
    }

    protected ReturnFixture deliveredOrder(String discountCode) {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        long la1 = seedListing(a.brand(), a.user(), "119.00", 5);
        long la2 = seedListing(a.brand(), a.user(), "59.50", 5);
        long lb1 = seedListing(b.brand(), b.user(), "100.00", 5);
        String admin = login("admin@it.local", "Admin123!");
        if (discountCode != null) {
            assertThat(createAdminDiscount(admin, Map.of("code", discountCode, "percent", 0.10))
                    .getStatusCode().value()).isEqualTo(201);
        }
        String customer = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(customer, discountCode, List.of(item(la1, 2), item(la2, 1), item(lb1, 1))));
        confirmPaid(oid);
        String brandA = login("brand-a@it.local", "Brand123!");
        String brandB = login("brand-b@it.local", "Brand123!");
        assertThat(ship(brandA, oid).getStatusCode().value()).isEqualTo(200);
        assertThat(ship(brandB, oid).getStatusCode().value()).isEqualTo(200);
        assertThat(adminSetStatus(admin, oid, "DELIVERED").getStatusCode().value()).isEqualTo(200);
        return new ReturnFixture(oid, a.brand().getId(), b.brand().getId(),
                itemIdOf(oid, la1), itemIdOf(oid, la2), itemIdOf(oid, lb1), admin, customer, brandA, brandB);
    }

    protected ResponseEntity<Map> requestReturn(String token, long orderId, Long itemId, Integer qty) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "NO_LONGER_WANTED");
        if (itemId != null) body.put("orderItemId", itemId);
        if (qty != null) body.put("quantity", qty);
        return rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
    }

    protected String returnNumberOf(long orderId, long brandId) {
        return jdbc.queryForObject("SELECT return_number FROM returns WHERE order_id = ? AND brand_partner_id = ? "
                + "ORDER BY id DESC LIMIT 1", String.class, orderId, brandId);
    }

    protected int quantityOnReturn(String returnNumber, long itemId) {
        return jdbc.queryForObject("SELECT ri.quantity_returned FROM return_items ri JOIN returns r "
                + "ON r.id = ri.return_order_id WHERE r.return_number = ? AND ri.order_item_id = ?",
                Integer.class, returnNumber, itemId);
    }

    protected ResponseEntity<Map> approve(String adminToken, String rn) {
        return rest.exchange("/admin/returns/" + rn + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    protected ResponseEntity<Map> brandReceive(String brandToken, String rn) {
        return rest.exchange("/brand/returns/" + rn + "/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(brandToken)), Map.class);
    }

    protected Map<String, Object> returnRow(String rn) {
        return jdbc.queryForMap("SELECT * FROM returns WHERE return_number = ?", rn);
    }

    /** POST release-refund; the body is sent only when there is an override reason. */
    protected ResponseEntity<Map> releaseRefund(String adminToken, String rn, String overrideReason) {
        return rest.exchange("/admin/returns/" + rn + "/release-refund", HttpMethod.POST,
                new HttpEntity<>(overrideReason == null ? null : Map.of("overrideReason", overrideReason),
                        auth(adminToken)), Map.class);
    }

    protected String transactionIdOf(long orderId) {
        return jdbc.queryForObject("SELECT transaction_id FROM payments WHERE order_id = ?", String.class, orderId);
    }

    /** What the Mollie webhook does (Task 8): confirm or fail every PENDING refund of the order's payment. */
    protected void confirmRefunds(long orderId) {
        returnRefundService.onProviderRefundStatus(transactionIdOf(orderId));
    }

    /**
     * Request (itemId/qty null = whole order) + approve + brand receive. Returns brand A's return
     * number when the request touched brand A, else the first one touched.
     */
    protected String receivedReturn(ReturnFixture f, Long itemId, Integer qty) {
        assertThat(requestReturn(f.customerToken(), f.orderId(), itemId, qty).getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT return_number, brand_partner_id FROM returns "
                + "WHERE order_id = ? AND status = 'REQUESTED' ORDER BY id", f.orderId());
        String first = null;
        for (Map<String, Object> row : rows) {
            String rn = (String) row.get("return_number");
            boolean brandA = ((Number) row.get("brand_partner_id")).longValue() == f.brandAId();
            assertThat(approve(f.adminToken(), rn).getStatusCode().value()).isEqualTo(200);
            assertThat(brandReceive(brandA ? f.brandAToken() : f.brandBToken(), rn).getStatusCode().value())
                    .isEqualTo(200);
            if (first == null || brandA) first = rn;
        }
        return first;
    }
}
