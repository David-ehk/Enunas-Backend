package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Returns on a multi-brand order.
 *
 * <p>Before the per-brand split, {@code buildReturnAddress} read the FIRST order item's brand and
 * told the customer to ship the whole order there — Brand B's goods went to Brand A's address. The
 * same one-row-per-order shape made {@code recordRefund} pro-rate a single brand's refund across
 * every brand on the order. Both are asserted against here.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
class MultiBrandReturnTest extends AbstractDiscountIntegrationTest {

    /** No SMTP in the test env; confirmShipment's mail is NOT best-effort and would 500 the ship. */
    @MockitoBean private EmailService emailService;

    // ===== Fixtures =====

    /** Brand with §22f master data only — returns fall back to the business address. */
    private BrandFixture brandWithLegalAddressOnly(String name, String slug) {
        BrandFixture f = seedBrand(name, slug, "0.18");
        BrandPartner b = f.brand();
        b.setLegalName(name + " GmbH");
        b.setAddressStreet("Steuerweg 1");
        b.setAddressPostalCode("10115");
        b.setAddressCity("Berlin");
        b.setAddressCountry("DE");
        brandPartnerRepository.save(b);
        return f;
    }

    /** Brand that has nominated a separate returns warehouse. */
    private BrandFixture brandWithReturnWarehouse(String name, String slug) {
        BrandFixture f = brandWithLegalAddressOnly(name, slug);
        BrandPartner b = f.brand();
        b.setReturnRecipient(name + " Retourenlager");
        b.setReturnStreet("Lagerstrasse 99");
        b.setReturnPostalCode("80331");
        b.setReturnCity("Muenchen");
        b.setReturnCountry("DE");
        brandPartnerRepository.save(b);
        return f;
    }

    private List<Map<String, Object>> returnRows(long orderId) {
        return jdbc.queryForList("SELECT * FROM returns WHERE order_id = ? ORDER BY id", orderId);
    }

    /**
     * Drives an order all the way to DELIVERED, the only status a return can be requested from.
     * Ships via {@code shippingBrandToken} first (exercising the real per-brand confirmShipment
     * path this fixture cares about), then admin's bulk SHIPPED override completes any OTHER brand
     * on the order — DELIVERED is only reachable once every brand has genuinely shipped (see
     * OrderService.syncShipmentStatus/validateForwardTransition), and these fixtures only ever
     * ship one brand explicitly since the return/refund behavior under test is orthogonal to
     * shipment mechanics.
     */
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

    private ResponseEntity<Map> requestReturn(String customerToken, long orderId, Long orderItemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "WRONG_SIZE");
        body.put("description", "Passt nicht");
        if (orderItemId != null) body.put("orderItemId", orderItemId);
        return rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(customerToken)), Map.class);
    }

    private ResponseEntity<Map> adminReturnAction(String adminToken, String returnNumber, String action) {
        return rest.exchange("/admin/returns/" + returnNumber + "/" + action, HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    // ===== Tests =====

    @Test
    @DisplayName("A two-brand order produces one return per brand, each with its own address")
    void multiBrandOrderSplitsIntoOneReturnPerBrand() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();

        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);

        ResponseEntity<Map> resp = requestReturn(customerToken, orderId, null);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

        List<Map<String, Object>> rows = returnRows(orderId);
        assertThat(rows).as("one return per brand").hasSize(2);
        assertThat(rows.stream().map(r -> r.get("return_number")).distinct()).hasSize(2);
        assertThat(rows.stream().map(r -> r.get("brand_partner_id")))
                .containsExactlyInAnyOrder(a.brand().getId(), b.brand().getId());

        // THE BUG: both brands used to receive Brand A's address.
        Map<String, Object> rowA = rows.stream()
                .filter(r -> a.brand().getId().equals(((Number) r.get("brand_partner_id")).longValue()))
                .findFirst().orElseThrow();
        Map<String, Object> rowB = rows.stream()
                .filter(r -> b.brand().getId().equals(((Number) r.get("brand_partner_id")).longValue()))
                .findFirst().orElseThrow();

        assertThat(rowA.get("ship_to_street")).isEqualTo("Lagerstrasse 99");
        assertThat(rowA.get("ship_to_city")).isEqualTo("Muenchen");
        assertThat(rowB.get("ship_to_street")).isEqualTo("Steuerweg 1");
        assertThat(rowB.get("ship_to_city")).isEqualTo("Berlin");

        // The API exposes both, and refuses to pick one for the legacy scalar field.
        List<Map<String, Object>> returns = (List<Map<String, Object>>) resp.getBody().get("returns");
        assertThat(returns).hasSize(2);
        assertThat(returns.stream().map(r -> r.get("shipToAddress")).distinct()).hasSize(2);
        assertThat(resp.getBody().get("returnShipToAddress"))
                .as("no single address can be right for a two-brand return")
                .isNull();
    }

    @Test
    @DisplayName("The nominated warehouse wins; a brand without one falls back to its §22f address")
    void returnAddressFallsBackToLegalAddressWhenNotNominated() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> resp = requestReturn(customerToken, orderId, null);

        List<Map<String, Object>> returns = (List<Map<String, Object>>) resp.getBody().get("returns");
        Map<String, Object> retA = returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow();
        Map<String, Object> retB = returns.stream()
                .filter(r -> "BrandB".equals(r.get("brandName"))).findFirst().orElseThrow();

        assertThat((String) retA.get("shipToAddress"))
                .contains("BrandA Retourenlager").contains("Lagerstrasse 99").contains("Muenchen");
        assertThat((String) retB.get("shipToAddress"))
                .contains("BrandB GmbH").contains("Steuerweg 1").contains("Berlin");
    }

    @Test
    @DisplayName("Moving the warehouse afterwards does not move an in-flight return")
    void shipToAddressIsFrozenAtRequestTime() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        requestReturn(customerToken, orderId, null);

        // Brand relocates AFTER the customer was given an address.
        BrandPartner moved = brandPartnerRepository.findById(a.brand().getId()).orElseThrow();
        moved.setReturnStreet("Neue Halle 5");
        moved.setReturnCity("Hamburg");
        brandPartnerRepository.save(moved);

        Map<String, Object> row = returnRows(orderId).get(0);
        assertThat(row.get("ship_to_street")).isEqualTo("Lagerstrasse 99");
        assertThat(row.get("ship_to_city")).isEqualTo("Muenchen");
    }

    @Test
    @DisplayName("Approving one brand leaves the order at the least-advanced return")
    void orderStatusTracksTheSlowestBrandReturn() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId, null);

        List<Map<String, Object>> returns = (List<Map<String, Object>>) requested.getBody().get("returns");
        String returnA = (String) returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");

        ResponseEntity<Map> afterApprove = adminReturnAction(adminToken, returnA, "approve");
        assertThat(afterApprove.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderRow(orderId).get("status"))
                .as("Brand B has not been approved yet, so the order is not RETURN_APPROVED")
                .isEqualTo("RETURN_REQUESTED");
    }

    @Test
    @DisplayName("Refunding one brand leaves the other brand's balance untouched")
    void refundIsScopedToTheReturningBrand() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);

        BigDecimal pendingBBefore = brandPending(b.brand().getId());
        assertThat(pendingBBefore).isGreaterThan(BigDecimal.ZERO);

        ResponseEntity<Map> requested = requestReturn(customerToken, orderId, null);
        List<Map<String, Object>> returns = (List<Map<String, Object>>) requested.getBody().get("returns");
        String returnA = (String) returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");

        adminReturnAction(adminToken, returnA, "approve");
        adminReturnAction(adminToken, returnA, "receive");
        ResponseEntity<Map> refunded = adminReturnAction(adminToken, returnA, "refund");
        assertThat(refunded.getStatusCode().is2xxSuccessful()).isTrue();

        // THE MONEY BUG: this used to pro-rate Brand A's refund across Brand B as well.
        assertThat(brandPending(b.brand().getId()))
                .as("Brand B's goods never came back — its balance must not move")
                .isEqualByComparingTo(pendingBBefore);
        assertThat(brandPending(a.brand().getId()))
                .as("Brand A's product payout is reversed; shipping (4.99) remains as the return did not reverse it")
                .isEqualByComparingTo(new BigDecimal("4.99"));
        assertThat(orderRow(orderId).get("status"))
                .as("the order tracks its least-advanced return — Brand B is still REQUESTED")
                .isEqualTo("RETURN_REQUESTED");
    }

    @Test
    @DisplayName("A single-brand order still populates the legacy scalar address field")
    void singleBrandOrderKeepsBackwardCompatibleFields() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 2))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> resp = requestReturn(customerToken, orderId, null);

        assertThat(returnRows(orderId)).hasSize(1);
        assertThat((String) resp.getBody().get("returnShipToAddress")).contains("Lagerstrasse 99");
        assertThat(resp.getBody().get("returnNumber")).isNotNull();
    }

    @Test
    @DisplayName("An item cannot be returned twice, but another brand's item still can")
    void perItemGuardReplacesTheOrderWideGuard() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);

        List<Map<String, Object>> items = orderItemRows(orderId);
        long itemA = ((Number) items.get(0).get("id")).longValue();
        long itemB = ((Number) items.get(1).get("id")).longValue();

        assertThat(requestReturn(customerToken, orderId, itemA).getStatusCode().is2xxSuccessful()).isTrue();

        // The old order-wide existsByOrder guard rejected this outright.
        assertThat(requestReturn(customerToken, orderId, itemB).getStatusCode().is2xxSuccessful())
                .as("a different brand's item must still be returnable")
                .isTrue();
        assertThat(returnRows(orderId)).hasSize(2);

        assertThat(requestReturn(customerToken, orderId, itemA).getStatusCode())
                .as("the same item must not be returnable twice")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("A second request for the same brand's other item merges into the existing REQUESTED return")
    void secondRequestForSameBrandMergesIntoTheOpenReturn() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listing1 = seedListing(a.brand(), a.user(), "100.00", 5);
        long listing2 = seedListing(a.brand(), a.user(), "40.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listing1, 1), item(listing2, 1))));
        deliver(orderId, brandAToken, adminToken);

        List<Map<String, Object>> items = orderItemRows(orderId);
        long item1 = ((Number) items.get(0).get("id")).longValue();
        long item2 = ((Number) items.get(1).get("id")).longValue();

        requestReturn(customerToken, orderId, item1);
        requestReturn(customerToken, orderId, item2);

        // One brand, one still-REQUESTED return — items merge instead of spawning a second return
        // number for the same brand+order (the invariant V15 enforces at the DB layer).
        List<Map<String, Object>> rows = returnRows(orderId);
        assertThat(rows).as("both items merge into the same brand return").hasSize(1);

        long returnOrderId = ((Number) rows.get(0).get("id")).longValue();
        List<Map<String, Object>> returnItemRows = jdbc.queryForList(
                "SELECT * FROM return_items WHERE return_order_id = ?", returnOrderId);
        assertThat(returnItemRows).hasSize(2);
    }

    @Test
    @DisplayName("A brand's return already APPROVED blocks further items for that brand, not for others")
    void inProgressReturnBlocksFurtherItemsForThatBrandOnly() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA1 = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingA2 = seedListing(a.brand(), a.user(), "40.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null,
                List.of(item(listingA1, 1), item(listingA2, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);

        List<Map<String, Object>> items = orderItemRows(orderId);
        long itemA1 = ((Number) items.get(0).get("id")).longValue();
        long itemA2 = ((Number) items.get(1).get("id")).longValue();
        long itemB = ((Number) items.get(2).get("id")).longValue();

        ResponseEntity<Map> firstRequest = requestReturn(customerToken, orderId, itemA1);
        List<Map<String, Object>> firstReturns = (List<Map<String, Object>>) firstRequest.getBody().get("returns");
        String returnA = (String) firstReturns.get(0).get("returnNumber");
        adminReturnAction(adminToken, returnA, "approve");

        // Brand A's return is now APPROVED — a second item for Brand A must not silently join it or
        // spawn a second concurrent return; Brand B, untouched by any of this, must still go through.
        ResponseEntity<Map> mixed = requestReturn(customerToken, orderId, null); // whole remaining order
        // itemA2 and itemB are both still open; itemA1 is already covered by returnA.
        // The service must reject itemA2 (blocked brand) while still returning itemB (Brand B).
        assertThat(returnRows(orderId).stream()
                .filter(r -> b.brand().getId().equals(((Number) r.get("brand_partner_id")).longValue())))
                .as("Brand B's item must still be returnable")
                .hasSize(1);
        assertThat(jdbc.queryForList(
                "SELECT * FROM return_items ri JOIN returns r ON r.id = ri.return_order_id " +
                        "WHERE r.return_number = ? AND ri.order_item_id = ?", returnA, itemA2))
                .as("Brand A's second item must NOT have joined the already-approved return")
                .isEmpty();
    }

    @Test
    @DisplayName("The DB rejects a second active return row for the same order+brand outright")
    void databaseRejectsADuplicateActiveReturnRow() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        requestReturn(customerToken, orderId, null);

        // Bypass the application layer entirely — this is the safety net for a race the app-level
        // check cannot fully close, not a re-test of application behaviour.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO returns (return_number, order_id, user_id, brand_partner_id, status, requested_at) " +
                        "VALUES (?, ?, (SELECT user_id FROM customers LIMIT 1), ?, 'APPROVED', now())",
                "RET-TEST-DUP", orderId, a.brand().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("The owning brand can upload a label once its return is approved")
    void brandCanUploadLabelAfterApproval() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId, null);
        String returnA = (String) requested.getBody().get("returnNumber");

        // Uploading before approval must be rejected — nothing to label yet.
        assertThat(uploadLabel(brandAToken, returnA).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        adminReturnAction(adminToken, returnA, "approve");
        ResponseEntity<Map> uploaded = uploadLabel(brandAToken, returnA);
        assertThat(uploaded.getStatusCode().is2xxSuccessful()).isTrue();

        Map<String, Object> row = returnRows(orderId).get(0);
        assertThat(row.get("label_status")).isEqualTo("UPLOADED_BY_BRAND");
        assertThat(row.get("label_carrier")).isEqualTo("DHL");
        assertThat(row.get("label_tracking_number")).isEqualTo("LABEL-TRACK-1");
    }

    @Test
    @DisplayName("A brand cannot upload a label onto another brand's return")
    void brandCannotUploadLabelForAnotherBrandsReturn() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");
        String brandBToken = login("brand-b@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);
        ResponseEntity<Map> requested = requestReturn(customerToken, orderId, null);
        List<Map<String, Object>> returns = (List<Map<String, Object>>) requested.getBody().get("returns");
        String returnA = (String) returns.stream()
                .filter(r -> "BrandA".equals(r.get("brandName"))).findFirst().orElseThrow().get("returnNumber");
        adminReturnAction(adminToken, returnA, "approve");

        // 403, not 500: uploadReturnLabel throws SecurityException for a brand that doesn't own the
        // return, and GlobalExceptionHandler maps that to FORBIDDEN. This asserted 500 back when the
        // exception fell through to the generic handler.
        assertThat(uploadLabel(brandBToken, returnA).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private ResponseEntity<Map> uploadLabel(String brandToken, String returnNumber) {
        Map<String, Object> body = Map.of(
                "carrier", "DHL", "trackingNumber", "LABEL-TRACK-1", "labelUrl", "https://labels.example/1.pdf");
        return rest.exchange("/brand/returns/" + returnNumber + "/label", HttpMethod.POST,
                new HttpEntity<>(body, auth(brandToken)), Map.class);
    }

    @Test
    @DisplayName("The deprecated order-scoped endpoint refuses to guess on a multi-brand order")
    void orderScopedAdminEndpointConflictsWhenSeveralReturnsExist() {
        BrandFixture a = brandWithReturnWarehouse("BrandA", "brand-a");
        BrandFixture b = brandWithLegalAddressOnly("BrandB", "brand-b");
        seedAdmin();
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 5);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingA, 1), item(listingB, 1))));
        deliver(orderId, brandAToken, adminToken);
        requestReturn(customerToken, orderId, null);

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + orderId + "/return/approve",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
