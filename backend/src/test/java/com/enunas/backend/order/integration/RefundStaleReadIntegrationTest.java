package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.payment.RefundResult;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Reproduces and verifies the fix for the stale-read bug in {@code RefundPersistenceHelper.persist()}:
 * open-in-view keeps ONE Hibernate session across the whole HTTP request, so {@code persist()}'s own
 * {@code returnOrderRepository.findById(...)} can hand back the SAME managed {@code ReturnOrder}
 * instance {@code processRefund} already validated as RECEIVED — even after a concurrent refund has,
 * in the meantime, flipped it to REFUNDED. See
 * {@code .superpowers/sdd/return-refund-stale-read-fix/brief.md} for the full writeup. Mirrors the
 * proven shape in {@code AdminCancelRefundIntegrationTest.brandShipsDuringRefund_recordsRefundButDoesNotCancel}:
 * a {@code doAnswer} on {@code paymentProvider.refundPayment(...)} runs a real DB mutation while the
 * refund call is in flight, landing exactly between {@code processRefund}'s read/validate and
 * {@code persist()}'s write.
 */
class RefundStaleReadIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    @Test
    @DisplayName("A refund racing a concurrent winner must not overwrite it, and must not send a second email")
    void concurrentRefundWinner_secondCallRefusesAndLeavesWinnerUntouched() {
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        seedAdmin();
        seedCustomer();
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listing, 1))));
        deliver(orderId, brandToken, adminToken);

        String returnNumber = (String) requestReturn(customerToken, orderId).getBody().get("returnNumber");
        assertThat(adminReturnAction(adminToken, returnNumber, "approve").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(adminReturnAction(adminToken, returnNumber, "receive").getStatusCode().is2xxSuccessful()).isTrue();

        long returnId = returnRow(returnNumber).get("id") instanceof Number n ? n.longValue() : -1L;

        // The "concurrent winner": another admin's (or a double-click's) refund call that completes
        // and commits its own persist() WHILE this call's paymentProvider.refundPayment(...) is still
        // in flight — exactly the window processRefund's own javadoc calls out (the Mollie HTTP call
        // deliberately runs outside any DB transaction). We fake the winner's DB effect directly
        // rather than driving a second real HTTP call through the whole flow, since the race window
        // that matters is inside THIS call's single Hibernate session (open-in-view spans one
        // request; a second real HTTP call would get its own fresh session and wouldn't reproduce it).
        BigDecimal winnerAmount = new BigDecimal("42.00");
        doAnswer(inv -> {
            jdbc.update("UPDATE returns SET status = 'REFUNDED', refunded_at = now(), refund_amount = ? "
                    + "WHERE id = ?", winnerAmount, returnId);
            return new RefundResult("ref_winner_mock");
        }).when(paymentProvider).refundPayment(any(RefundCommand.class));

        ResponseEntity<Map> resp = refund(adminToken, returnNumber);

        assertThat(resp.getStatusCode().value())
                .as("second call must be refused once it discovers the return is no longer RECEIVED: %s",
                        resp.getBody())
                .isEqualTo(409);

        Map<String, Object> row = returnRow(returnNumber);
        assertThat(row.get("status")).isEqualTo("REFUNDED");
        assertThat((BigDecimal) row.get("refund_amount"))
                .as("must remain whatever the concurrent winner set, untouched by this refused call")
                .isEqualByComparingTo(winnerAmount);

        // Only the (simulated) winner ever completed a real persist() in this test — that path was
        // faked via raw SQL and never ran RefundPersistenceHelper.persist(), so no RefundCompletedEvent
        // was ever published for it either. The bug under test is THIS call wrongly running persist()
        // a second time and publishing a second RefundCompletedEvent (a second "refund completed"
        // email) on top of it. Scoped to the refund-completed subject specifically — the return's own
        // "approved" email (ReturnApprovedEmailListener) legitimately fired earlier in this test.
        verify(emailService, never())
                .sendPlainTextEmail(anyString(), contains("Rückerstattung"), anyString());
    }

    // ===== helpers (mirrors MultiBrandReturnTest / AdminCancelRefundIntegrationTest) =====

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

    private ResponseEntity<Map> adminReturnAction(String adminToken, String returnNumber, String action) {
        return rest.exchange("/admin/returns/" + returnNumber + "/" + action, HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    private ResponseEntity<Map> refund(String adminToken, String returnNumber) {
        return rest.exchange("/admin/returns/" + returnNumber + "/refund", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    private Map<String, Object> returnRow(String returnNumber) {
        return jdbc.queryForMap("SELECT * FROM returns WHERE return_number = ?", returnNumber);
    }
}
