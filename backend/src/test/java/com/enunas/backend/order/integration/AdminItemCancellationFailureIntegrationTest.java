package com.enunas.backend.order.integration;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
@ExtendWith(OutputCaptureExtension.class)
class AdminItemCancellationFailureIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;
    // Named ledgerSpy, not ledgerService: the base class already has an @Autowired ledgerService field
    // (it receives this same spy), and reusing the name would shadow it.
    @MockitoSpyBean LedgerService ledgerSpy;

    private static String message(ResponseEntity<Map> resp) {
        return String.valueOf(resp.getBody().get("message"));
    }

    private void backdateClaim(long itemId) {
        jdbc.update("UPDATE order_items SET cancelled_at = now() - interval '6 minutes' WHERE id = ?", itemId);
    }

    /** a1 left claimed with an unknown refund outcome. */
    private Fixture ambiguousClaimOnA1() {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        return f;
    }

    @Test
    void definitiveRejection_releasesTheClaim_andARetrySucceeds() {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentRejectedException("amount too high", null)).when(paymentProvider).refundPayment(any());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        assertThat(itemRow(f.a1()).get("cancellation_claim_key")).isNull();

        doCallRealMethod().when(paymentProvider).refundPayment(any());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void ambiguousFailure_keepsTheClaim_andARetryMakesNoSecondCall(CapturedOutput output) {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> first = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(first.getStatusCode().value()).isEqualTo(409);
        assertThat(message(first)).contains("reconcile");
        assertThat(output).contains("ITEM_CANCEL_AMBIGUOUS");
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isNull();

        ResponseEntity<Map> retry = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(retry.getStatusCode().value()).isEqualTo(409);
        assertThat(message(retry)).contains("in progress");
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void aClaimOlderThanFiveMinutes_isReportedStuck() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());
        ResponseEntity<Map> again = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(message(again)).contains("stuck");
    }

    @Test
    void finalizeFailingAfterARefund_keepsTheClaim_andLogsForReconcile(CapturedOutput output) {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new RuntimeException("ledger down")).when(ledgerSpy)
                .recordItemCancellationReversal(any(), any(), anyString());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(500);
        assertThat(output).contains("ITEM_CANCEL_RECORDING_FAILED");
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isNull();
        assertThat(stockOfItem(f.a1())).isEqualTo(4);
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void reconcileRecord_settlesAStuckClaim_withoutCallingMollie() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        ResponseEntity<Map> resp = reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RECORD", "re_manual");

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isEqualTo("re_manual");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        assertThat(reversals(f.orderId())).extracting(r -> r.get("external_reference_id")).containsExactly("re_manual");
        verify(paymentProvider, times(1)).refundPayment(any()); // only the original, ambiguous call
    }

    @Test
    void reconcileRecord_isRefusedWhileInProgress_whenSettled_andWithoutARefundId() {
        Fixture f = ambiguousClaimOnA1();
        String key = claimKeyOf(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_x").getStatusCode().value()).isEqualTo(409);
        backdateClaim(f.a1());
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", null).getStatusCode().value()).isEqualTo(400);
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_x").getStatusCode().value()).isEqualTo(200);
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_y").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void reconcileRelease_freesTheItems_withoutCallingMollie() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RELEASE", null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        assertThat(itemRow(f.a1()).get("cancellation_claim_key")).isNull();
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void reconcileRelease_afterTheBrandShippedItsOtherItems_isFlagged(CapturedOutput output) {
        Fixture f = ambiguousClaimOnA1();
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue(); // a2, a3 active
        backdateClaim(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RELEASE", null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(output).contains("ITEM_CANCEL_ABORTED_AFTER_SHIPMENT");
    }

    @Test
    void reconcile_withForeignClaimKey_isRejected() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        ResponseEntity<Map> resp = reconcile(f.adminToken(), f.orderId(), "item-cancel-ENS-OTHER-x", "RELEASE", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        verify(paymentProvider, times(1)).refundPayment(any());
        verify(paymentProvider, never()).getPaymentDetails(anyString());
    }
}
