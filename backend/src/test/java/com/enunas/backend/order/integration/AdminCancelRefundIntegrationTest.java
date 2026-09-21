package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundResult;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class AdminCancelRefundIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;
    @MockitoSpyBean TransactionTemplate transactionTemplate;

    @Test
    void paidCancel_refundsReversesAndRecords() {
        Fixture f = paidOrder();

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", "mailed us");

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue();
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation_reason")).isEqualTo("CUSTOMER_REQUEST");
        assertThat(order.get("cancelled_by_admin_email")).isEqualTo("admin@it.local");
        String refundId = (String) order.get("refund_transaction_id");
        assertThat(refundId).startsWith("ref_mock_");

        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'"
                        + " AND external_reference_id LIKE ?", Integer.class, f.orderId(), refundId + "%"))
                .isPositive();
        assertThat(brandPending(f.brandId())).isEqualByComparingTo("0.00");
        assertThat(stock(f.orderId())).isEqualTo(5);
        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
        // Subject only: the refund sentence in the body is added by Task 5, which tightens this assertion.
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), anyString());
    }

    @Test
    void refundFailure_changesNothing() {
        Fixture f = paidOrder();
        doThrow(new RuntimeException("Mollie down")).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(order.get("refund_transaction_id")).isNull();
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        assertThat(stock(f.orderId())).isEqualTo(4);
    }

    @Test
    void paymentArrivesBetweenPhases_refusesAndChangesNothing() {
        Fixture f = pendingOrder();
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger updated = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            // The first execute is phase 1. Once it has committed, land the "webhook" - before phase 3
            // starts. Fire once only; later executes run untouched.
            Object result = inv.callRealMethod();
            if (fired.compareAndSet(false, true)) {
                updated.set(jdbc.update(
                        "UPDATE payments SET status='PAID', paid_at=now() WHERE order_id = ?", f.orderId()));
            }
            return result;
        }).when(transactionTemplate).execute(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(updated.get()).as("payment row was flipped to PAID between the phases").isEqualTo(1);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("PENDING");
        assertThat(order.get("refund_transaction_id")).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void shippedDuringRefund_recordsRefundButDoesNotCancel() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE orders SET status='AWAITING_ADMIN' WHERE id = ?", f.orderId());
        doAnswer(inv -> {
            jdbc.update("INSERT INTO order_shipments (order_id, brand_partner_id, status, shipped_at, created_at,"
                    + " updated_at) VALUES (?, ?, 'SHIPPED', now(), now(), now())"
                    + " ON CONFLICT (order_id, brand_partner_id) DO UPDATE SET status='SHIPPED'",
                    f.orderId(), f.brandId());
            return inv.callRealMethod();
        }).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("AWAITING_ADMIN");
        assertThat((String) order.get("refund_transaction_id")).startsWith("ref_mock_");
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        assertThat(stock(f.orderId())).isEqualTo(4);
    }

    @Test
    void blankRefundId_isTreatedAsFailure() {
        Fixture f = paidOrder();
        doReturn(new RefundResult(null)).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        assertThat(orderRow(f.orderId()).get("refund_transaction_id")).isNull();
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("PAID");
        assertThat(stock(f.orderId())).isEqualTo(4);
    }

    @Test
    void paidOrderWithoutCapturedPayment_isRefused() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE payments SET status='PENDING' WHERE order_id = ?", f.orderId());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void unpaidCancel_doesNotCallMollie() {
        Fixture f = pendingOrder();

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue();
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("CANCELLED");
        assertThat(orderRow(f.orderId()).get("refund_transaction_id")).isNull();
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    // ===== helpers =====

    record Fixture(long orderId, long brandId, String adminToken, String customerToken) {}

    private Fixture pendingOrder() {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String cust = login("customer@it.local", "Customer123!");
        String admin = login("admin@it.local", "Admin123!");
        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        return new Fixture(oid, a.brand().getId(), admin, cust);
    }

    private Fixture paidOrder() {
        Fixture f = pendingOrder();
        confirmPaid(f.orderId());
        return f;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> cancel(String adminToken, long oid, String reason, String note) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        if (note != null) body.put("note", note);
        return rest.exchange("/admin/orders/" + oid + "/cancel", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    Map<String, Object> paymentRow(long oid) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", oid);
    }

    int stock(long oid) {
        return jdbc.queryForObject(
                "SELECT pv.stock_quantity FROM product_variants pv JOIN order_items oi ON oi.variant_id = pv.id"
                        + " WHERE oi.order_id = ?", Integer.class, oid);
    }
}
