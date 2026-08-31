package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

/**
 * Order.status/shippingCarrier/trackingNumber/shippedAt used to be single, order-wide values —
 * confirmShipment/reportShippingProblem, called by ONE brand, flipped the WHOLE order to SHIPPED
 * or SHIPPING_PROBLEM, corrupting every other brand's still-pending shipment on the same
 * multi-brand order. A knock-on symptom: once Brand A shipped (order -> SHIPPED), Brand B could no
 * longer even confirmShipment or reportShippingProblem for their own still-unshipped items, because
 * both guarded on "order.status == PAID" which was no longer true.
 *
 * Fixed by OrderShipment (one row per order+brand, mirroring the existing per-brand ReturnOrder
 * pattern) with Order.status as an honest rollup (OrderService.syncShipmentStatus): PARTIALLY_SHIPPED
 * while some but not all brands have shipped, SHIPPED only once every brand has.
 */
class BrandShipmentIsolationIntegrationTest extends AbstractDiscountIntegrationTest {

    /** No SMTP in the test env; confirmShipment's mail is not best-effort and would 500 the ship. */
    @MockitoBean private EmailService emailService;

    private record TwoBrandOrder(long orderId, String tokenA, String tokenB, String adminToken) {}

    private TwoBrandOrder placeTwoBrandOrder() {
        BrandPartner a = seedBrand("Alpha", "alpha", "0.15").brand();
        BrandPartner b = seedBrand("Beta", "beta", "0.15").brand();
        seedCustomer();
        seedAdmin();
        long listingA = seedListing(a, a.getUser(), "89.95", 5);
        long listingB = seedListing(b, b.getUser(), "29.95", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String tokenA = login("alpha@it.local", "Brand123!");
        String tokenB = login("beta@it.local", "Brand123!");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        return new TwoBrandOrder(orderId, tokenA, tokenB, adminToken);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> ship(String token, long orderId, String tracking) {
        return rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", tracking), auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> reportProblem(String token, long orderId, String description) {
        return rest.exchange("/brand/orders/" + orderId + "/problem", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", description), auth(token)), Map.class);
    }

    private String orderStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, orderId);
    }

    @Test
    void brandA_ships_ordersStaysPartiallyShipped_brandBUnaffected() {
        TwoBrandOrder o = placeTwoBrandOrder();

        ResponseEntity<Map> resp = ship(o.tokenA(), o.orderId(), "TRACK-A");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        // Brand B's own view must still show their item as not yet shipped — not silently flipped.
        List<Map<String, Object>> shipmentsB = (List<Map<String, Object>>) rest.exchange(
                        "/brand/orders", HttpMethod.GET, new HttpEntity<>(auth(o.tokenB())), Map.class)
                .getBody().get("content");
        Map<String, Object> orderAsB = ((List<Map<String, Object>>) shipmentsB).get(0);
        List<Map<String, Object>> bShipments = (List<Map<String, Object>>) orderAsB.get("shipments");
        assertThat(bShipments).hasSize(1);
        assertThat(bShipments.get(0).get("status")).isEqualTo("AWAITING_SHIPMENT");
    }

    @Test
    void bothBrandsShip_orderBecomesFullyShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();

        ship(o.tokenA(), o.orderId(), "TRACK-A");
        assertThat(orderStatus(o.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        ship(o.tokenB(), o.orderId(), "TRACK-B");
        assertThat(orderStatus(o.orderId())).isEqualTo("SHIPPED");
    }

    /** Reproduces the exact knock-on bug: before the fix, Brand A shipping flipped order.status to
     *  SHIPPED, and Brand B's confirmShipment guarded on "order.status == PAID" — so Brand B could
     *  never ship their own still-pending item at all. */
    @Test
    void brandB_canStillShip_afterBrandAAlreadyShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");

        ResponseEntity<Map> resp = ship(o.tokenB(), o.orderId(), "TRACK-B");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void brandCannotShipTwice() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");

        ResponseEntity<Map> resp = ship(o.tokenA(), o.orderId(), "TRACK-A-AGAIN");

        assertThat(resp.getStatusCode().value()).isEqualTo(409); // IllegalStateException -> CONFLICT
    }

    @Test
    void brandBReportsProblem_doesNotEscalateWholeOrderStatus_butSetsFlag() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");

        ResponseEntity<Map> resp = reportProblem(o.tokenB(), o.orderId(), "Lost in warehouse");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // Order.status stays the honest shipment rollup — never SHIPPING_PROBLEM for a
        // single brand's local issue.
        assertThat(orderStatus(o.orderId())).isEqualTo("PARTIALLY_SHIPPED");
        assertThat((Boolean) jdbc.queryForObject(
                "SELECT has_shipping_problem FROM orders WHERE id = ?", Boolean.class, o.orderId())).isTrue();
    }

    /** Brand A must never see that Brand B has a problem — same isolation principle as #2. */
    @Test
    void brandBsProblem_notVisibleToBrandA() {
        TwoBrandOrder o = placeTwoBrandOrder();

        reportProblem(o.tokenB(), o.orderId(), "Lost in warehouse");

        ResponseEntity<Map> asA = rest.exchange("/brand/orders", HttpMethod.GET,
                new HttpEntity<>(auth(o.tokenA())), Map.class);
        Map<String, Object> orderAsA = ((List<Map<String, Object>>) asA.getBody().get("content")).get(0);
        assertThat(orderAsA.get("hasShippingProblem")).isEqualTo(false);
        assertThat(orderAsA.toString()).doesNotContain("Lost in warehouse");
    }

    /**
     * The per-brand rework replaced confirmShipment's old {@code status == PAID} guard — correctly,
     * since PARTIALLY_SHIPPED is now a legitimate state to ship from — but an earlier revision
     * dropped it entirely instead of widening it, letting a brand mark items shipped on an UNPAID
     * order and fire a dispatch email for it.
     */
    @Test
    void brandCannotShip_anUnpaidOrder() {
        BrandPartner a = seedBrand("Alpha", "alpha", "0.15").brand();
        seedCustomer();
        long listingA = seedListing(a, a.getUser(), "89.95", 5);
        String customerToken = login("customer@it.local", "Customer123!");
        String tokenA = login("alpha@it.local", "Brand123!");

        // Deliberately NOT confirmPaid() — the order stays PENDING.
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingA, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        ResponseEntity<Map> resp = ship(tokenA, orderId, "TRACK-A");

        assertThat(resp.getStatusCode().value()).as("body: %s", resp.getBody()).isEqualTo(409);
        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        Long shipmentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_shipments WHERE order_id = ?", Long.class, orderId);
        assertThat(shipmentRows).as("no shipment row may be written for an unpaid order").isZero();
    }

    /** A brand that only reported a PROBLEM has dispatched nothing — the order must stay PAID, not
     *  read as PARTIALLY_SHIPPED just because a shipment row now exists. */
    @Test
    void problemReportAlone_doesNotMakeOrderLookPartiallyShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();

        reportProblem(o.tokenB(), o.orderId(), "Lost in warehouse");

        assertThat(orderStatus(o.orderId())).isEqualTo("PAID");
        assertThat((Boolean) jdbc.queryForObject(
                "SELECT has_shipping_problem FROM orders WHERE id = ?", Boolean.class, o.orderId())).isTrue();
    }

    /**
     * The admin's order-wide SHIPPED override force-ships every brand that hasn't recorded a
     * dispatch. It used to do that silently: rows flipped to SHIPPED, order read SHIPPED, and the
     * customer was never told about the brands the admin had just shipped on their behalf. Brand A
     * shipped itself and already had its mail, so it must NOT be mailed again — exactly one new
     * mail, for Beta.
     */
    @Test
    void adminShippedOverride_mailsOnlyTheBrandsItForceShips() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");
        clearInvocations(emailService);

        assertThat(adminStatus(o.adminToken(), o.orderId(), "SHIPPED").getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), subject.capture(), body.capture());

        assertThat(subject.getValue()).contains("Teilsendung").contains("Beta");
        // The admin path has no carrier or tracking number to offer. The mail must say so rather
        // than printing "null" at the customer.
        assertThat(body.getValue()).doesNotContain("null");
        assertThat(body.getValue()).contains("keine Tracking-Nummer");
    }

    /** The per-brand dispatch mail names the articles in THIS parcel, never another brand's. */
    @Test
    void shipmentMail_listsOnlyTheShippingBrandsItems() {
        TwoBrandOrder o = placeTwoBrandOrder();
        clearInvocations(emailService);

        ship(o.tokenA(), o.orderId(), "TRACK-A");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), anyString(), body.capture());
        assertThat(body.getValue()).contains("TRACK-A").contains("DHL");
        assertThat(body.getValue()).contains("/orders/");
        assertThat(body.getValue()).doesNotContain("null");
    }

    private ResponseEntity<Map> adminStatus(String adminToken, long orderId, String status) {
        return rest.exchange("/admin/orders/" + orderId + "/status?status=" + status,
                HttpMethod.PATCH, new HttpEntity<>(auth(adminToken)), Map.class);
    }

    /**
     * A brand's own problem report no longer escalates the whole order (that was the bug), which
     * left a mixed-state order with no admin exit at all. The admin now has an explicit escalation
     * lever — and, critically, it is a round trip, not a one-way street.
     */
    @Test
    void adminEscalation_isARoundTrip_andLeavesBrandRowsIntact() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");
        reportProblem(o.tokenB(), o.orderId(), "Lost in warehouse");
        assertThat(orderStatus(o.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        // Escalate: the order-level rollup pauses...
        assertThat(adminStatus(o.adminToken(), o.orderId(), "SHIPPING_PROBLEM")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("SHIPPING_PROBLEM");

        // ...but what physically happened is untouched: Brand A's row still says SHIPPED,
        // with its own carrier and tracking number.
        Map<String, Object> rowA = jdbc.queryForMap(
                "SELECT s.status, s.tracking_number FROM order_shipments s JOIN brand_partners b " +
                "ON b.id = s.brand_partner_id WHERE s.order_id = ? AND b.brand_name = 'Alpha'", o.orderId());
        assertThat(rowA.get("status")).isEqualTo("SHIPPED");
        assertThat(rowA.get("tracking_number")).isEqualTo("TRACK-A");

        // Way back: PAID means "return to the shipping flow", and the rollup is re-derived from the
        // brand rows — so it lands on PARTIALLY_SHIPPED, never flatly back on PAID.
        assertThat(adminStatus(o.adminToken(), o.orderId(), "PAID").getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        // And the remaining brand can still finish the job.
        assertThat(ship(o.tokenB(), o.orderId(), "TRACK-B").getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("SHIPPED");
    }

    /** Cancelling restores stock for every item and reverses the whole ledger — never valid once a
     *  brand has physically dispatched. Escalation must not become a back door to that. */
    @Test
    void adminCannotCancelViaEscalation_onceABrandHasShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();
        ship(o.tokenA(), o.orderId(), "TRACK-A");
        adminStatus(o.adminToken(), o.orderId(), "SHIPPING_PROBLEM");

        ResponseEntity<Map> resp = adminStatus(o.adminToken(), o.orderId(), "CANCELLED");

        assertThat(resp.getStatusCode().value()).as("body: %s", resp.getBody()).isEqualTo(409);
        assertThat(orderStatus(o.orderId())).isEqualTo("SHIPPING_PROBLEM");
    }

    /** With nothing dispatched, escalate → cancel remains available. */
    @Test
    void adminCanStillCancelViaEscalation_whenNothingShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();
        reportProblem(o.tokenA(), o.orderId(), "Cannot fulfil");
        adminStatus(o.adminToken(), o.orderId(), "MANUAL_REVIEW");

        assertThat(adminStatus(o.adminToken(), o.orderId(), "CANCELLED")
                .getStatusCode().value()).isEqualTo(200);
        assertThat(orderStatus(o.orderId())).isEqualTo("CANCELLED");
    }

    @Test
    void adminBulkShip_marksEveryBrandShipped() {
        TwoBrandOrder o = placeTwoBrandOrder();

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + o.orderId() + "/status?status=SHIPPED",
                HttpMethod.PATCH, new HttpEntity<>(auth(o.adminToken())), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        long shippedRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM order_shipments WHERE order_id = ? AND status = 'SHIPPED'",
                Long.class, o.orderId());
        assertThat(shippedRows).isEqualTo(2);
    }
}
