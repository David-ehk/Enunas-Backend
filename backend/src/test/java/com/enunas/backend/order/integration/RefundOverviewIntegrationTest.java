package com.enunas.backend.order.integration;

import com.enunas.backend.payment.RefundStatusResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class RefundOverviewIntegrationTest extends AbstractReturnRefundIntegrationTest {

    private ResponseEntity<Map> overview(String adminToken, String state) {
        return rest.exchange("/admin/refunds" + (state == null ? "" : "?state=" + state), HttpMethod.GET,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    private List<Map<String, Object>> rows(String adminToken, String state) {
        ResponseEntity<Map> r = overview(adminToken, state);
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return (List<Map<String, Object>>) r.getBody().get("content");
    }

    @Test
    void eachStateListsExactlyItsReturn() {
        ReturnFixture f = deliveredOrder(null);
        String refunded = receivedReturn(f, f.a1(), 1);
        releaseRefund(f.adminToken(), refunded, null);
        confirmRefunds(f.orderId());
        String pending = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
        releaseRefund(f.adminToken(), pending, null);
        String due = receivedReturn(f, f.b1(), 1);

        List<Map<String, Object>> dueRows = rows(f.adminToken(), "DUE");
        assertThat(dueRows).hasSize(1);
        assertThat(dueRows.get(0)).containsEntry("returnNumber", due).containsEntry("state", "DUE")
                .containsEntry("brandName", "BrandB").containsEntry("receivedBy", "BRAND");

        List<Map<String, Object>> pendingRows = rows(f.adminToken(), "PENDING");
        assertThat(pendingRows).hasSize(1);
        assertThat(pendingRows.get(0)).containsEntry("returnNumber", pending).containsEntry("state", "PENDING");
        assertThat(((Number) pendingRows.get(0).get("total")).doubleValue()).isEqualTo(119.00);
        assertThat(pendingRows.get(0).get("releasedBy")).isEqualTo("admin@it.local");

        List<Map<String, Object>> refundedRows = rows(f.adminToken(), "REFUNDED");
        assertThat(refundedRows).hasSize(1);
        assertThat(refundedRows.get(0)).containsEntry("returnNumber", refunded).containsEntry("state", "REFUNDED");
        assertThat(refundedRows.get(0).get("refundedAt")).isNotNull();

        assertThat(rows(f.adminToken(), "FAILED")).isEmpty();
        assertThat(rows(f.adminToken(), "IN_PROGRESS")).isEmpty();
        assertThat(overview(f.adminToken(), "NOPE").getStatusCode().value()).isEqualTo(400);
        assertThat(overview(f.adminToken(), null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void failedAndInFlightClaimsAreListed() {
        ReturnFixture f = deliveredOrder(null);
        String failed = receivedReturn(f, f.a1(), 1);
        mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
        releaseRefund(f.adminToken(), failed, null);
        confirmRefunds(f.orderId());
        String inFlight = receivedReturn(f, f.b1(), 1);
        releaseRefund(f.adminToken(), inFlight, null);
        jdbc.update("UPDATE returns SET refund_state = NULL WHERE return_number = ?", inFlight);

        assertThat(rows(f.adminToken(), "FAILED")).extracting(r -> r.get("returnNumber")).containsExactly(failed);
        List<Map<String, Object>> inProgress = rows(f.adminToken(), "IN_PROGRESS");
        assertThat(inProgress).extracting(r -> r.get("returnNumber")).containsExactly(inFlight);
        assertThat(inProgress.get(0)).containsEntry("state", "IN_PROGRESS");
        assertThat(rows(f.adminToken(), "DUE")).isEmpty();
    }
}
