package com.enunas.backend.order.integration;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.exception.PaymentRetryLaterException;
import com.enunas.backend.payment.RefundStatusResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings("rawtypes")
class ReturnRefundReleaseIntegrationTest extends AbstractReturnRefundIntegrationTest {

    @Test
    void releaseAfterBrandConfirmationRefundsExactlyWhatWasPaid_discounted() {
        ReturnFixture f = deliveredOrder("TEN");                          // 10% -> a1 line paid 214.20 for 2 units
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        String rn = returnNumberOf(f.orderId(), f.brandAId());
        approve(f.adminToken(), rn);
        brandReceive(f.brandAToken(), rn);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        BigDecimal paidPerUnit = itemMoney(f.a1(), "customer_gross_after_discount")
                .divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        assertThat((BigDecimal) returnRow(rn).get("refund_amount")).isEqualByComparingTo(paidPerUnit);
        assertThat((BigDecimal) returnRow(rn).get("refund_shipping_amount")).isEqualByComparingTo("0.00");
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
        assertThat(returnRow(rn).get("status")).isEqualTo("RECEIVED");
        mockPaymentStore.setRefundStatus((String) returnRow(rn).get("refund_transaction_id"),
                RefundStatusResult.Status.REFUNDED);
        confirmRefunds(f.orderId());
        assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
        assertThat(paymentStatus(f.orderId())).isEqualTo("PAID");
    }

    private Map orderJson(String token, String path) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Object overrideReasonIn(Map order) {
        return ((List<Map>) order.get("returns")).get(0).get("refundOverrideReason");
    }

    @Test
    @SuppressWarnings("unchecked")
    void overrideReasonIsAdminOnly() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        String rn = returnNumberOf(f.orderId(), f.brandAId());
        approve(f.adminToken(), rn);
        assertThat(releaseRefund(f.adminToken(), rn, "Kunde hat Beleg").getStatusCode().value()).isEqualTo(200);
        assertThat(overrideReasonIn(orderJson(f.adminToken(), "/admin/orders/" + f.orderId()))).isEqualTo("Kunde hat Beleg");
        assertThat(overrideReasonIn(orderJson(f.customerToken(), "/orders/" + f.orderId()))).isNull();
        Map brandPage = orderJson(f.brandAToken(), "/brand/orders");
        assertThat(overrideReasonIn(((List<Map>) brandPage.get("content")).get(0))).isNull();
    }

    @Test
    void legacyRefundedReturnWithoutClaimKeyCountsTowardTheOverTotalNet() {
        ReturnFixture f = deliveredOrder(null);
        String first = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), first, null);               // REFUNDED (mock default)
        BigDecimal paid = jdbc.queryForObject("SELECT amount FROM payments WHERE order_id = ?", BigDecimal.class,
                f.orderId());
        jdbc.update("UPDATE returns SET refund_claim_key = NULL, refund_state = 'REFUNDED', status = 'REFUNDED', refund_amount = ? WHERE return_number = ?",
                paid.subtract(BigDecimal.ONE), first);            // a pre-V37 row: REFUNDED, no claim key
        String second = receivedReturn(f, f.b1(), 1);             // would refund 100.00 more
        assertThat(releaseRefund(f.adminToken(), second, null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void ledgerReversesExactlyTheReturnedUnits() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        Map<String, Object> rev = reversals(f.orderId()).get(0);
        BigDecimal half = itemMoney(f.a1(), "commission_net").divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
        assertThat((BigDecimal) rev.get("commission_net")).isEqualByComparingTo(half.negate());
        BigDecimal sum = ((BigDecimal) rev.get("commission_net")).add((BigDecimal) rev.get("commission_vat"))
                .add((BigDecimal) rev.get("brand_payout"));
        assertThat(sum.negate()).isEqualByComparingTo((BigDecimal) returnRow(rn).get("refund_amount"));
    }

    @Test
    void multiBrandWorkedExampleAddsUpToTheOrderTotal() {
        ReturnFixture f = deliveredOrder(null);
        String a1 = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), a1, null);
        confirmRefunds(f.orderId());
        String b = receivedReturn(f, f.b1(), 1);
        releaseRefund(f.adminToken(), b, null);
        confirmRefunds(f.orderId());
        assertThat((BigDecimal) returnRow(b).get("refund_shipping_amount"))
                .isEqualByComparingTo(shippingOf(f.orderId(), f.brandBId()));
        assertThat(paymentStatus(f.orderId())).isEqualTo("PAID");
        String a2 = receivedReturn(f, null, null);                          // remaining: 1x a1, 1x a2
        releaseRefund(f.adminToken(), a2, null);
        confirmRefunds(f.orderId());
        BigDecimal total = jdbc.queryForObject("SELECT SUM(refund_amount) FROM returns WHERE order_id = ?",
                BigDecimal.class, f.orderId());
        assertThat(total).isEqualByComparingTo((BigDecimal) orderRow(f.orderId()).get("total"));
        assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");
        assertThat(orderStatus(f.orderId())).isEqualTo("REFUNDED");
        // brand B's reversals reference brand B only
        assertThat(reversals(f.orderId())).extracting(r -> ((Number) r.get("brand_partner_id")).longValue())
                .contains(f.brandAId(), f.brandBId());
    }

    @Test
    void doubleReleaseIs409AndCallsMollieOnce() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        assertThat(reversals(f.orderId())).hasSize(1);
    }

    @Test
    void safetyNetCountsOpenClaims() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        // an open (unfinished) claim on another return that already covers more than the rest of the payment
        String other = receivedReturn(f, f.b1(), 1);
        jdbc.update("UPDATE returns SET refund_claim_key = 'open-claim', refund_amount = ? WHERE return_number = ?",
                ((BigDecimal) orderRow(f.orderId()).get("total")).subtract(new BigDecimal("1.00")), other);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_claim_key")).isNull();
    }

    @Test
    void overrideFromApprovedNeedsAReasonAndDoesNotRestoreStock() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        String rn = returnNumberOf(f.orderId(), f.brandAId());
        approve(f.adminToken(), rn);
        int stock = stockOfItem(f.a1());
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        assertThat(releaseRefund(f.adminToken(), rn, "Kunde hat Einlieferungsbeleg").getStatusCode().value())
                .isEqualTo(200);
        assertThat(stockOfItem(f.a1())).isEqualTo(stock);
        assertThat(returnRow(rn).get("refund_override_reason")).isEqualTo("Kunde hat Einlieferungsbeleg");
        confirmRefunds(f.orderId());
        assertThat(brandReceive(f.brandAToken(), rn).getStatusCode().value()).isEqualTo(200);   // late arrival
        assertThat(stockOfItem(f.a1())).isEqualTo(stock + 1);
        assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
    }

    @Test
    void rejectedByProviderReleasesTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        doThrow(new PaymentRejectedException("amount too high", null)).when(paymentProvider).refundPayment(any());
        // GlobalExceptionHandler maps PaymentException to 400.
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(400);
        assertThat(returnRow(rn).get("refund_claim_key")).isNull();
    }

    @Test
    void rateLimitedReleasesTheClaimWith503() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        doThrow(new PaymentRetryLaterException("429", null)).when(paymentProvider).refundPayment(any());
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(503);
        assertThat(returnRow(rn).get("refund_claim_key")).isNull();
    }

    @Test
    void ambiguousKeepsTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_claim_key")).isNotNull();
        assertThat(returnRow(rn).get("refund_transaction_id")).isNull();
    }

    @Test
    void shippingNotRefundedTwiceAfterCancellation() {
        // Seed the shipping reversal as a prior cancellation would; the return must not add shipping again.
        ReturnFixture f = deliveredOrder(null);
        jdbc.update("INSERT INTO ledger_entries (order_id, brand_partner_id, total_amount, platform_fee, "
                + "brand_payout, commission_rate, currency, entry_type, status, payout_eligible_at, "
                + "moved_to_available, external_reference_id, created_at) VALUES (?, ?, -4.99, 0, -4.99, 0, 'EUR', "
                + "'REFUND_REVERSAL', 'REVERSED', now(), false, 'prior:SHIPPING', now())", f.orderId(), f.brandBId());
        String rn = receivedReturn(f, f.b1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        assertThat((BigDecimal) returnRow(rn).get("refund_shipping_amount")).isEqualByComparingTo("0.00");
    }

    @Test
    void failedRefundCanBeResentWithoutNewLedgerEntries() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        confirmRefunds(f.orderId());
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("FAILED");
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
        assertThat(returnRow(rn).get("refund_resend_count")).isEqualTo(1);
        assertThat(reversals(f.orderId())).hasSize(1);
        mockPaymentStore.setRefundStatus((String) returnRow(rn).get("refund_transaction_id"),
                RefundStatusResult.Status.REFUNDED);
        confirmRefunds(f.orderId());
        assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
        assertThat(reversals(f.orderId())).hasSize(1);
    }
}
