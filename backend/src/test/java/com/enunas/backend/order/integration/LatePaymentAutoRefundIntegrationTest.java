package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.order.OrderExpiryService;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A Mollie "paid" that lands on an order which is already CANCELLED — the customer paid at minute
 * 29, the expiry job cancelled at minute 30, the webhook arrived at minute 31. Money was captured,
 * so this must end in a refund, never a silently cancelled paid order. Also covers {@code paidAt}
 * on the order DTO, which is what tells a client "cancelled, nothing charged" apart from
 * "cancelled after payment".
 */
class LatePaymentAutoRefundIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired OrderExpiryService orderExpiryService;
    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    @Test
    void paymentAfterExpiry_isRefundedAutomatically() {
        long oid = placeExpiredOrder();

        confirmPaid(oid); // the late webhook

        Map<String, Object> order = orderRow(oid);
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat((String) order.get("cancellation_note")).contains("AUTO_REFUNDED").doesNotContain("REFUND_REQUIRED");

        Map<String, Object> payment = paymentRow(oid);
        assertThat(payment.get("status")).isEqualTo("REFUNDED");
        assertThat(payment.get("paid_at")).isNotNull();

        // The cancelled order books nothing and takes no stock.
        assertThat(ledgerEntries(oid)).isZero();
        assertThat(stock(oid)).isEqualTo(5);

        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), contains("wird erstattet"), anyString());
    }

    @Test
    void duplicateLateWebhook_refundsOnce() {
        long oid = placeExpiredOrder();

        confirmPaid(oid);
        confirmPaid(oid);

        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
        verify(emailService, times(1)).sendPlainTextEmail(anyString(), contains("wird erstattet"), anyString());
        assertThat(paymentRow(oid).get("status")).isEqualTo("REFUNDED");
    }

    @Test
    void failedRefund_leavesRefundRequiredFlagForAdmin() {
        long oid = placeExpiredOrder();
        doThrow(new RuntimeException("Mollie unavailable")).when(paymentProvider).refundPayment(any());

        confirmPaid(oid); // must not throw — Mollie would otherwise retry a webhook that no-ops

        assertThat((String) orderRow(oid).get("cancellation_note")).startsWith("REFUND_REQUIRED");
        Map<String, Object> payment = paymentRow(oid);
        assertThat(payment.get("status")).isEqualTo("PAID");
        assertThat(payment.get("paid_at")).isNotNull();
        // The customer is still told their money is coming back.
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), contains("wird erstattet"), anyString());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void paidAt_distinguishesPaidThenCancelledFromNeverPaid() {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String custToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");

        // Paid, then cancelled by an admin.
        long paidThenCancelled = orderId(postOrder(custToken, null, List.of(item(listing, 1))));
        confirmPaid(paidThenCancelled);
        ResponseEntity<Map> cancel = rest.exchange(
                "/admin/orders/" + paidThenCancelled + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(cancel.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancel.getBody()).isTrue();
        assertThat(cancel.getBody().get("paidAt")).isNotNull();

        Map customerView = getOrder(custToken, paidThenCancelled);
        assertThat(customerView.get("status")).isEqualTo("CANCELLED");
        assertThat(customerView.get("paidAt")).isNotNull();

        // Never paid, expired.
        long neverPaid = orderId(postOrder(custToken, null, List.of(item(listing, 1))));
        expire(neverPaid);
        Map expiredView = getOrder(custToken, neverPaid);
        assertThat(expiredView.get("status")).isEqualTo("CANCELLED");
        assertThat(expiredView.get("paidAt")).isNull();
    }

    // ===== helpers =====

    private long placeExpiredOrder() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String custToken = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(custToken, null, List.of(item(listing, 1))));
        expire(oid);
        assertThat(orderRow(oid).get("status")).isEqualTo("CANCELLED");
        return oid;
    }

    /** Backdates the order past the hold window and runs the real expiry job. */
    private void expire(long oid) {
        jdbc.update("UPDATE orders SET created_at = ? WHERE id = ?", LocalDateTime.now().minusMinutes(31), oid);
        orderExpiryService.cancelExpiredPendingOrders();
    }

    @SuppressWarnings("rawtypes")
    private Map getOrder(String token, long oid) {
        ResponseEntity<Map> resp = rest.exchange("/orders/" + oid, HttpMethod.GET,
                new HttpEntity<>(null, auth(token)), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("get order: %s", resp.getBody()).isTrue();
        return resp.getBody();
    }

    private Map<String, Object> paymentRow(long oid) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", oid);
    }

    private int ledgerEntries(long oid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries WHERE order_id = ?", Integer.class, oid);
    }

    private int stock(long oid) {
        return jdbc.queryForObject(
                "SELECT pv.stock_quantity FROM product_variants pv JOIN order_items oi ON oi.variant_id = pv.id"
                        + " WHERE oi.order_id = ?", Integer.class, oid);
    }
}
