package com.enunas.backend.order.integration;

import com.enunas.backend.compliance.Vat22fExportService;
import com.enunas.backend.order.ReturnOrderRepository;
import com.enunas.backend.order.ReturnRefundService;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class ReturnsWithCancelledItemsIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @Autowired ReturnOrderRepository returnOrderRepository;
    @Autowired Vat22fExportService vat22fExportService;
    @Autowired ReturnRefundService returnRefundService;

    /** Brand A's a3 and brand B's only item b1 are cancelled; a1 and a2 get delivered. */
    private Fixture deliveredWithCancellations(String discountCode, boolean a3SettledNotJustClaimed) {
        Fixture f = paidTwoBrandOrder(discountCode);
        settleCancelledViaJdbc(f.b1());
        if (a3SettledNotJustClaimed) settleCancelledViaJdbc(f.a3()); else claimViaJdbc(f.a3());
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(adminSetStatus(f.adminToken(), f.orderId(), "DELIVERED").getStatusCode().is2xxSuccessful()).isTrue();
        return f;
    }

    private ResponseEntity<Map> requestReturn(Fixture f, Long orderItemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "WRONG_SIZE");
        body.put("description", "Passt nicht");
        if (orderItemId != null) body.put("orderItemId", orderItemId);
        return rest.exchange("/orders/" + f.orderId() + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(f.customerToken())), Map.class);
    }

    /** approve + receive + release; plays the provider confirmation when the release goes through. */
    private ResponseEntity<Map> refundTheReturn(Fixture f, String returnNumber) {
        for (String action : new String[] {"approve", "receive"}) {
            ResponseEntity<Map> r = rest.exchange("/admin/returns/" + returnNumber + "/" + action, HttpMethod.POST,
                    new HttpEntity<>(null, auth(f.adminToken())), Map.class);
            assertThat(r.getStatusCode().is2xxSuccessful()).as("%s: %s", action, r.getBody()).isTrue();
        }
        ResponseEntity<Map> released = rest.exchange("/admin/returns/" + returnNumber + "/release-refund",
                HttpMethod.POST, new HttpEntity<>(null, auth(f.adminToken())), Map.class);
        if (released.getStatusCode().is2xxSuccessful()) {
            returnRefundService.onProviderRefundStatus(jdbc.queryForObject(
                    "SELECT transaction_id FROM payments WHERE order_id = ?", String.class, f.orderId()));
        }
        return released;
    }

    @Test
    void returningACancelledItem_isRejected() {
        Fixture f = deliveredWithCancellations(null, true);
        assertThat(requestReturn(f, f.a3()).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void wholeOrderReturn_skipsCancelledItems() {
        Fixture f = deliveredWithCancellations(null, true);
        assertThat(requestReturn(f, null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(returnOrderRepository.returnedQuantityByItem(f.orderId()))
                .extracting(r -> (Long) r[0]).containsExactlyInAnyOrder(f.a1(), f.a2());
    }

    @Test
    void cancelledPlusReturnedAndRefunded_readsRefunded_andReleasesTheDiscount() {
        Fixture f = deliveredWithCancellations("RET10", true);
        ResponseEntity<Map> ret = requestReturn(f, null);
        refundTheReturn(f, (String) ret.getBody().get("returnNumber"));

        assertThat(orderStatus(f.orderId())).isEqualTo("REFUNDED");
        assertThat(usedCount("RET10")).isZero();
        assertThat(orderRow(f.orderId()).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    void aStuckClaim_keepsTheOrderFromReadingRefunded() {
        Fixture f = deliveredWithCancellations("RET10", false); // a3 only claimed — its money is not recorded
        ResponseEntity<Map> ret = requestReturn(f, null);
        // The unsettled cancellation claim blocks the release outright (409) until it is reconciled.
        assertThat(refundTheReturn(f, (String) ret.getBody().get("returnNumber")).getStatusCode().value())
                .isEqualTo(409);

        assertThat(orderStatus(f.orderId())).isNotEqualTo("REFUNDED");
        assertThat(usedCount("RET10")).isEqualTo(1);
    }

    @Test
    void vat22fExport_excludesCancelledItems_butKeepsReturnedOnes() {
        Fixture f = deliveredWithCancellations(null, true);
        String period = YearMonth.now(ZoneId.of("Europe/Berlin")).toString();
        assertThat(vat22fExportService.export(f.brandAId(), period)).hasSize(2); // a1, a2 — not a3

        ResponseEntity<Map> ret = requestReturn(f, f.a1());
        refundTheReturn(f, (String) ret.getBody().get("returnNumber"));
        assertThat(vat22fExportService.export(f.brandAId(), period)).hasSize(2); // returned a1 stays
    }
}
