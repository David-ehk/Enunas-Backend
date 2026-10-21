package com.enunas.backend.order.integration;

import com.enunas.backend.payment.RefundStatusResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class ReturnRefundStatusIntegrationTest extends AbstractReturnRefundIntegrationTest {

    private void postMollieWebhook(String transactionId) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("id", transactionId);
        assertThat(rest.exchange("/webhooks/mollie", HttpMethod.POST, new HttpEntity<>(form, h), Void.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void webhookOnAlreadyPaidPaymentConfirmsPendingRefund() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        postMollieWebhook(transactionIdOf(f.orderId()));
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
        assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
    }

    @Test
    void releaseOfAnImmediatelyFinalRefundIsRefundedWithoutAWebhook() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
    }

    private ResponseEntity<Map> refresh(String adminToken, String rn) {
        return rest.exchange("/admin/returns/" + rn + "/reconcile-refund", HttpMethod.POST,
                new HttpEntity<>(Map.of("action", "REFRESH"), auth(adminToken)), Map.class);
    }

    @Test
    void refreshPicksUpAProviderStatusThatChangedAfterRelease() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        releaseRefund(f.adminToken(), rn, null);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
        assertThat(refresh(f.adminToken(), rn).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
        mockPaymentStore.setRefundStatus((String) returnRow(rn).get("refund_transaction_id"),
                RefundStatusResult.Status.REFUNDED);
        assertThat(refresh(f.adminToken(), rn).getStatusCode().value()).isEqualTo(200);
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
    }

    @Test
    void refreshOfAReturnThatIsNotPendingIs409() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        assertThat(refresh(f.adminToken(), rn).getStatusCode().value()).isEqualTo(409);   // nothing released yet
        releaseRefund(f.adminToken(), rn, null);                                          // now REFUNDED
        assertThat(refresh(f.adminToken(), rn).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void confirmingTwiceSendsTheEmailOnce() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        confirmRefunds(f.orderId());
        confirmRefunds(f.orderId());
        verify(emailService, times(1)).sendPlainTextEmail(any(), contains("Rückerstattung"), any());
    }

    @Test
    void stillProcessingIsANoOp() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        releaseRefund(f.adminToken(), rn, null);
        confirmRefunds(f.orderId());
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
    }

    @Test
    void failedRefundOfAWholeOrderReturnPutsPaymentBackToPaid() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, null, null); // whole order, brand A's return
        String rnB = jdbc.queryForObject("SELECT return_number FROM returns WHERE order_id = ? AND brand_partner_id = ?",
                String.class, f.orderId(), f.brandBId());
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        releaseRefund(f.adminToken(), rn, null);
        releaseRefund(f.adminToken(), rnB, null);
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, f.orderId()))
                .isEqualTo("REFUNDED");
        mockPaymentStore.setRefundStatus((String) returnRow(rn).get("refund_transaction_id"), RefundStatusResult.Status.FAILED);
        confirmRefunds(f.orderId());
        assertThat(returnRow(rn).get("refund_state")).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, f.orderId()))
                .isEqualTo("PAID");
    }
}
