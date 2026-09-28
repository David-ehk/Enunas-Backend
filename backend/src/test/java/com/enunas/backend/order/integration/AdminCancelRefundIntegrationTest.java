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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), contains("erstatten"));
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
        java.util.concurrent.atomic.AtomicInteger updated = new java.util.concurrent.atomic.AtomicInteger();
        afterPhase1(() -> updated.set(jdbc.update(
                "UPDATE payments SET status='PAID', paid_at=now() WHERE order_id = ?", f.orderId())));

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
    void concurrentWinnerCancelled_phase3SeesItAndWritesNothing() {
        Fixture f = paidOrder();
        afterPhase1(() -> jdbc.update(
                "UPDATE orders SET status='CANCELLED', refund_transaction_id='ref_winner' WHERE id = ?", f.orderId()));

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("ref_winner");
        assertThat(stock(f.orderId())).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("PAID");
    }

    @Test
    void statusMovedToShippedAfterPhase1_recordsRefundButDoesNotCancel() {
        Fixture f = paidOrder();
        afterPhase1(() -> jdbc.update("UPDATE orders SET status='SHIPPED' WHERE id = ?", f.orderId()));

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("SHIPPED");
        assertThat((String) order.get("refund_transaction_id")).startsWith("ref_mock_");
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(stock(f.orderId())).isEqualTo(4);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
    }

    /** Runs {@code action} once, right after cancelOrder's phase 1 has committed (before phase 2/3). */
    private void afterPhase1(Runnable action) {
        java.util.concurrent.atomic.AtomicBoolean fired = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            Object result = inv.callRealMethod();
            boolean inCancel = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                    .anyMatch(e -> e.getMethodName().equals("cancelOrder"));
            if (inCancel && fired.compareAndSet(false, true)) action.run();
            return result;
        }).when(transactionTemplate).execute(any());
    }

    @Test
    void unpaidCancel_doesNotCallMollie() {
        Fixture f = pendingOrder();

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue();
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("CANCELLED");
        assertThat(orderRow(f.orderId()).get("refund_transaction_id")).isNull();
        verify(paymentProvider, times(0)).refundPayment(any());
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), body.capture());
        assertThat(body.getValue()).doesNotContain("erstatten");
    }

    @Test
    void alreadyRefundedPayment_isRejected() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE order_id = ?", f.orderId());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void amountMismatch_isRejected() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE payments SET amount = amount + 1 WHERE order_id = ?", f.orderId());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void shippedOrder_isRejected() {
        Fixture f = paidOrder();
        String brandToken = login("brand-a@it.local", "Brand123!");
        ResponseEntity<Map> shipped = rest.exchange("/brand/orders/" + f.orderId() + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        assertThat(shipped.getStatusCode().is2xxSuccessful()).as("ship: %s", shipped.getBody()).isTrue();

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    /**
     * Both existing shipment-guard tests ({@code shippedOrder_isRejected} above and
     * {@code alreadyRefundedPayment_isRejected}) happen to trip a DIFFERENT guard in
     * {@code validateCancellable} before execution ever reaches {@code anyBrandHasShipped} — e.g. the
     * status/amount checks. Here we admin-escalate the order's status to AWAITING_ADMIN (a status
     * that IS in the CANCELLABLE set) AFTER the brand has shipped, so the earlier checks all pass and
     * the shipment guard itself is what rejects the cancel.
     */
    @Test
    void shippedThenEscalated_stillRejectedByShipmentGuard() {
        Fixture f = paidOrder();
        String brandToken = login("brand-a@it.local", "Brand123!");
        ResponseEntity<Map> shipped = rest.exchange("/brand/orders/" + f.orderId() + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        assertThat(shipped.getStatusCode().is2xxSuccessful()).as("ship: %s", shipped.getBody()).isTrue();
        jdbc.update("UPDATE orders SET status='AWAITING_ADMIN' WHERE id = ?", f.orderId());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("AWAITING_ADMIN");
        verify(paymentProvider, never()).refundPayment(any());
    }

    /** Fix 1: {@code updateOrderStatus} must refuse CANCELLED outright — it carries no refund, no
     *  admin identity and no reason, so cancelling must always go through {@code cancelOrder} (or
     *  {@code cancelOrderItems}) instead. */
    @Test
    void updateOrderStatus_refusesCancelled() {
        Fixture f = paidOrder();
        com.enunas.backend.user.User admin = userRepository.findByEmail("admin@it.local").orElseThrow();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        admin, null, admin.getAuthorities()));

        try {
            assertThatThrownBy(() -> orderService.updateOrderStatus(f.orderId(), com.enunas.backend.order.OrderStatus.CANCELLED))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
    }

    /** Fix 2: an in-flight/stuck per-item cancellation claim must block the whole-order cancel —
     *  cancelling the order on top of it would double-refund or double-reverse the ledger. */
    @Test
    void unsettledItemCancellationClaim_blocksWholeOrderCancel() {
        Fixture f = paidOrder();
        long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, f.orderId());
        jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                + "cancellation_claim_key = 'test-claim' WHERE id = ?", itemId);

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(order.get("refund_transaction_id")).isNull();
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, never()).refundPayment(any());
    }

    /** Same guard, re-checked under the lock in phase 3: a claim opened WHILE the refund call is in
     *  flight must not let the whole-order cancel complete — the refund already happened, so it's
     *  recorded (same shape as the shipped-during-refund race), but the order is left uncancelled for
     *  a human to reconcile against the item claim. */
    @Test
    void itemClaimOpensDuringRefund_recordsRefundButDoesNotCancel() {
        Fixture f = paidOrder();
        long itemId = jdbc.queryForObject("SELECT id FROM order_items WHERE order_id = ?", Long.class, f.orderId());
        doAnswer(inv -> {
            jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                    + "cancellation_claim_key = 'test-claim' WHERE id = ?", itemId);
            return inv.callRealMethod();
        }).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat((String) order.get("refund_transaction_id")).startsWith("ref_mock_");
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
    }

    @Test
    void brandShipsDuringRefund_recordsRefundButDoesNotCancel() {
        Fixture f = paidOrder();
        String brandToken = login("brand-a@it.local", "Brand123!");
        // Ship the item *while* the refund call is in flight — the race the spec documents.
        org.mockito.Mockito.doAnswer(inv -> {
            rest.exchange("/brand/orders/" + f.orderId() + "/ship", HttpMethod.POST,
                    new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
            return new com.enunas.backend.payment.RefundResult("ref_mock_race");
        }).when(paymentProvider).refundPayment(any());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);

        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isNotEqualTo("CANCELLED");
        assertThat(order.get("refund_transaction_id")).isEqualTo("ref_mock_race");
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        assertThat(stock(f.orderId())).isEqualTo(4); // NOT restored — the parcel is in transit
    }

    @Test
    void deprecatedPatchPath_stillRefunds() {
        Fixture f = paidOrder();

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + f.orderId() + "/status?status=CANCELLED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(f.adminToken())), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("patch: %s", resp.getBody()).isTrue();
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation_reason")).isEqualTo("OTHER");
        assertThat(order.get("cancellation_note")).isEqualTo("via deprecated PATCH");
        assertThat((String) order.get("refund_transaction_id")).startsWith("ref_mock_");
        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
    }

    @Test
    void deprecatedPatchPath_unpaidOrder_cancelsWithoutRefund() {
        Fixture f = pendingOrder();

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + f.orderId() + "/status?status=CANCELLED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(f.adminToken())), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("patch: %s", resp.getBody()).isTrue();
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation_reason")).isEqualTo("OTHER");
        assertThat(order.get("refund_transaction_id")).isNull();
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void concurrentCancels_refundOnceAndOneWins() throws Exception {
        Fixture f = paidOrder();

        // cancelOrder's phase 2 (the Mollie call) runs BEFORE phase 3 takes any row lock (see its
        // javadoc: phase 2 must not hold a pooled DB connection), so under genuine concurrency BOTH
        // racing admin-cancel calls legitimately reach paymentProvider.refundPayment — verified
        // empirically: it is invoked twice here, both from OrderService.cancelOrder. The "refund once"
        // guarantee is therefore NOT "the method is called once" but "both calls resolve to the SAME
        // refund id" — the payment provider's idempotency-key contract (real Mollie) / the atomic
        // MockPaymentStore.replayOrMint (mock) ensures only one refund is ever minted, and the second
        // caller replays it. Capture what each invocation actually returned to verify that directly.
        List<String> refundIds = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        doAnswer(inv -> {
            RefundResult result = (RefundResult) inv.callRealMethod();
            refundIds.add(result.refundId());
            return result;
        }).when(paymentProvider).refundPayment(any());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> attempt = () -> {
            start.await();
            return cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value();
        };
        var a = pool.submit(attempt);
        var b = pool.submit(attempt);
        start.countDown();
        List<Integer> codes = List.of(a.get(), b.get());
        pool.shutdown();

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isEqualTo(2); // one product + one shipping entry, single reversal
        assertThat(brandPending(f.brandId())).isEqualByComparingTo("0.00");
        // Both calls happened, but both must have resolved to the SAME refund id — one refund at the
        // provider, not two — proving the atomic replay-or-mint fix actually works under real threads.
        assertThat(refundIds).hasSize(2);
        assertThat(refundIds).as("both calls must resolve to the same refund id").containsOnly(refundIds.get(0));
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
