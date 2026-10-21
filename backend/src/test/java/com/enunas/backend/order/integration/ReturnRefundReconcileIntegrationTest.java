package com.enunas.backend.order.integration;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.payment.RefundStatusResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@SuppressWarnings("rawtypes")
class ReturnRefundReconcileIntegrationTest extends AbstractReturnRefundIntegrationTest {

    private ResponseEntity<Map> reconcile(ReturnFixture f, String rn, String action, String refundId) {
        Map<String, Object> body = new HashMap<>();
        body.put("action", action);
        if (refundId != null) body.put("refundId", refundId);
        return rest.exchange("/admin/returns/" + rn + "/reconcile-refund", HttpMethod.POST,
                new HttpEntity<>(body, auth(f.adminToken())), Map.class);
    }

    private void backdateClaim(String rn) {
        jdbc.update("UPDATE returns SET refund_claimed_at = now() - interval '10 minutes' WHERE return_number = ?", rn);
    }

    /** A received return whose release ended ambiguously (claim in flight, no refund id), and old enough to be stuck. */
    private String stuckClaim(ReturnFixture f) {
        String rn = receivedReturn(f, f.a1(), 1);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        doCallRealMethod().when(paymentProvider).refundPayment(any());
        backdateClaim(rn);
        return rn;
    }

    private String mintRefund(ReturnFixture f, BigDecimal amount) {
        return paymentProvider.refundPayment(new RefundCommand(transactionIdOf(f.orderId()), amount, "r", "manual"))
                .refundId();
    }

    @Test
    void recordFirstAttemptFinalizesWithLedger() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        String refundId = mintRefund(f, (BigDecimal) returnRow(rn).get("refund_amount"));
        assertThat(reconcile(f, rn, "RECORD", refundId).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");   // final at Mollie: settled at once
        assertThat(returnRow(rn).get("refund_transaction_id")).isEqualTo(refundId);
        assertThat(reversals(f.orderId())).hasSize(1);
    }

    @Test
    void recordAFailedRefundIs409AndLeavesTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        String refundId = mintRefund(f, (BigDecimal) returnRow(rn).get("refund_amount"));
        mockPaymentStore.setRefundStatus(refundId, RefundStatusResult.Status.FAILED);
        assertThat(reconcile(f, rn, "RECORD", refundId).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_transaction_id")).isNull();
        assertThat(returnRow(rn).get("refund_state")).isNull();
    }

    @Test
    void recordWithoutRefundIdIs400() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        assertThat(reconcile(f, rn, "RECORD", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void recordUnknownRefundIs409AndLeavesTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        assertThat(reconcile(f, rn, "RECORD", "re_unknown").getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_claim_key")).isNotNull();
    }

    @Test
    void recordWithDifferentAmountIs409AndLeavesTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        String refundId = mintRefund(f, new BigDecimal("1.00"));
        assertThat(reconcile(f, rn, "RECORD", refundId).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_transaction_id")).isNull();
        assertThat(returnRow(rn).get("refund_state")).isNull();
        assertThat(reversals(f.orderId())).isEmpty();
    }

    @Test
    void recordAnAlreadyRecordedRefundIs409() {
        ReturnFixture f = deliveredOrder(null);
        String first = receivedReturn(f, f.b1(), 1);
        releaseRefund(f.adminToken(), first, null);
        String taken = (String) returnRow(first).get("refund_transaction_id");
        String rn = stuckClaim(f);
        assertThat(reconcile(f, rn, "RECORD", taken).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_transaction_id")).isNull();
    }

    @Test
    void releaseClearsTheClaimSoItCanBeReleasedAgain() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        assertThat(reconcile(f, rn, "RELEASE", null).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_claim_key")).isNull();
        assertThat(returnRow(rn).get("refund_amount")).isNull();
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void freshClaimIsStillInProgress() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckClaim(f);
        jdbc.update("UPDATE returns SET refund_claimed_at = now() WHERE return_number = ?", rn);
        assertThat(reconcile(f, rn, "RELEASE", null).getStatusCode().value()).isEqualTo(409);
        assertThat(returnRow(rn).get("refund_claim_key")).isNotNull();
    }

    /** FAILED refund, then an ambiguous resend whose claim time is old. */
    private String stuckResend(ReturnFixture f) {
        String rn = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        confirmRefunds(f.orderId());
        backdateClaim(rn);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
        doCallRealMethod().when(paymentProvider).refundPayment(any());
        return rn;
    }

    @Test
    void ambiguousResendRestartsTheStuckClock() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckResend(f);
        assertThat(reconcile(f, rn, "RELEASE", null).getStatusCode().value()).isEqualTo(409);
        backdateClaim(rn);
        assertThat(reconcile(f, rn, "RELEASE", null).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void recordOnResendStoresTheNewRefundWithoutLedgerEntries() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckResend(f);
        backdateClaim(rn);
        String refundId = mintRefund(f, (BigDecimal) returnRow(rn).get("refund_amount"));
        assertThat(reconcile(f, rn, "RECORD", refundId).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
        assertThat(returnRow(rn).get("refund_transaction_id")).isEqualTo(refundId);
        assertThat(reversals(f.orderId())).hasSize(1);
    }

    @Test
    void releaseOnResendRestoresFailedAndKeepsTheClaim() {
        ReturnFixture f = deliveredOrder(null);
        String rn = stuckResend(f);
        backdateClaim(rn);
        assertThat(reconcile(f, rn, "RELEASE", null).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("FAILED");
        assertThat(returnRow(rn).get("refund_claim_key")).isNotNull();
        assertThat(returnRow(rn).get("refund_amount")).isNotNull();
    }
}
