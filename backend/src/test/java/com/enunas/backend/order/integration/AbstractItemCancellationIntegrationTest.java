package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture for the per-item cancellation tests: one paid order with three items from brand A
 * (a1 €119.00, a2 €59.50, a3 €29.75) and one from brand B (b1 €100.00). Seeded stock is 5 per variant,
 * so a paid item's variant reads 4 and a restored one reads 5 again. Shipping is the global
 * default, €4.99 per brand.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class AbstractItemCancellationIntegrationTest extends AbstractDiscountIntegrationTest {

    record Fixture(long orderId, long brandAId, long brandBId, long a1, long a2, long a3, long b1,
                   String adminToken, String customerToken, String brandAToken, String brandBToken) {}

    protected Fixture paidTwoBrandOrder(String discountCode) {
        Fixture f = unpaidTwoBrandOrder(discountCode);
        confirmPaid(f.orderId());
        return f;
    }

    protected Fixture unpaidTwoBrandOrder(String discountCode) {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        long la1 = seedListing(a.brand(), a.user(), "119.00", 5);
        long la2 = seedListing(a.brand(), a.user(), "59.50", 5);
        long la3 = seedListing(a.brand(), a.user(), "29.75", 5);
        long lb1 = seedListing(b.brand(), b.user(), "100.00", 5);
        String admin = login("admin@it.local", "Admin123!");
        if (discountCode != null) {
            assertThat(createAdminDiscount(admin, Map.of("code", discountCode, "percent", 0.10))
                    .getStatusCode().value()).isEqualTo(201);
        }
        String customer = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(customer, discountCode,
                List.of(item(la1, 1), item(la2, 1), item(la3, 1), item(lb1, 1))));
        return new Fixture(oid, a.brand().getId(), b.brand().getId(),
                itemIdOf(oid, la1), itemIdOf(oid, la2), itemIdOf(oid, la3), itemIdOf(oid, lb1),
                admin, customer, login("brand-a@it.local", "Brand123!"), login("brand-b@it.local", "Brand123!"));
    }

    protected long itemIdOf(long orderId, long listingId) {
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND listing_id_snapshot = ?",
                Long.class, orderId, listingId);
    }

    /** Settled cancellation written straight to the DB — for flows that only READ cancellation state. */
    protected void settleCancelledViaJdbc(long... itemIds) {
        for (long id : itemIds) {
            jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                    + "cancellation_claim_key = 'jdbc-claim', refund_transaction_id = 're_jdbc_' || id WHERE id = ?", id);
        }
    }

    /** A claim that is in flight or stuck: cancelled_at set, no refund recorded. */
    protected void claimViaJdbc(long... itemIds) {
        for (long id : itemIds) {
            jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                    + "cancellation_claim_key = 'jdbc-claim' WHERE id = ?", id);
        }
    }

    protected ResponseEntity<Map> ship(String brandToken, long orderId) {
        return rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T-" + orderId), auth(brandToken)),
                Map.class);
    }

    protected ResponseEntity<Map> reportProblem(String brandToken, long orderId) {
        return rest.exchange("/brand/orders/" + orderId + "/problem", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", "Lost at the warehouse"), auth(brandToken)), Map.class);
    }

    protected ResponseEntity<Map> adminSetStatus(String adminToken, long orderId, String status) {
        return rest.exchange("/admin/orders/" + orderId + "/status?status=" + status, HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    protected Map<String, Object> itemRow(long itemId) {
        return jdbc.queryForMap("SELECT * FROM order_items WHERE id = ?", itemId);
    }

    protected BigDecimal itemMoney(long itemId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM order_items WHERE id = ?", BigDecimal.class, itemId);
    }

    protected String productName(long itemId) {
        return jdbc.queryForObject("SELECT product_snapshot_name FROM order_items WHERE id = ?", String.class, itemId);
    }

    protected int stockOfItem(long itemId) {
        return jdbc.queryForObject("SELECT pv.stock_quantity FROM product_variants pv "
                + "JOIN order_items oi ON oi.variant_id = pv.id WHERE oi.id = ?", Integer.class, itemId);
    }

    protected List<Map<String, Object>> reversals(long orderId) {
        return jdbc.queryForList("SELECT * FROM ledger_entries WHERE order_id = ? "
                + "AND entry_type = 'REFUND_REVERSAL' ORDER BY id", orderId);
    }

    protected String orderStatus(long orderId) {
        return (String) orderRow(orderId).get("status");
    }

    protected String paymentStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }

    protected BigDecimal shippingOf(long orderId, long brandId) {
        return jdbc.queryForObject("SELECT amount FROM order_shipping_snapshots "
                + "WHERE order_id = ? AND brand_partner_id = ?", BigDecimal.class, orderId, brandId);
    }

    protected ResponseEntity<Map> cancelItems(String adminToken, long orderId, List<Long> itemIds) {
        return rest.exchange("/admin/orders/" + orderId + "/cancel-items", HttpMethod.POST,
                new HttpEntity<>(Map.of("orderItemIds", itemIds, "reason", "OUT_OF_STOCK",
                        "note", "brand cannot fulfil"), auth(adminToken)), Map.class);
    }

    protected ResponseEntity<Map> reconcile(String adminToken, long orderId, String claimKey, String action,
                                            String refundId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("claimKey", claimKey);
        body.put("action", action);
        if (refundId != null) body.put("refundId", refundId);
        return rest.exchange("/admin/orders/" + orderId + "/cancel-items/reconcile", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    protected String claimKeyOf(long itemId) {
        return jdbc.queryForObject("SELECT cancellation_claim_key FROM order_items WHERE id = ?", String.class, itemId);
    }
}
