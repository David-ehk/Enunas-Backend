package com.enunas.backend.discount.integration;

import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full order -> payment -> ledger -> refund flow for both discount types, against a real Postgres
 * (Testcontainers) with Mollie mocked via the mock-payments profile. Money is asserted in euros
 * (BigDecimal); status codes reflect the real GlobalExceptionHandler (IllegalState -> 409,
 * IllegalArgument -> 400).
 */
class DiscountPaymentFlowIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private PlatformTransactionManager txManager;

    // ---- 1. Admin discount, full flow + full-refund round-trip ----
    @Test
    void adminDiscountFullFlow() {
        User admin = seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 50); // gross 119 → net 100
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> created = createAdminDiscount(adminToken, Map.of("code", "TESTADMIN10", "percent", 0.10));
        assertThat(created.getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> order = postOrder(custToken, "TESTADMIN10", List.of(item(listing, 1)));
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        long oid = orderId(order);

        // ADMIN 10% on gross 119 (net 100, 18% commission). Customer pays 119×0.90 = 107.10 in
        // product terms; + this order's one brand's shipping (GLOBAL_DEFAULT — no
        // BrandShippingProfile seeded in this test) 4.99 = 112.09 order total. gross reduction
        // 11.90; platform absorbs the whole 10.00 NET discount (shipping is never discounted).
        Map<String, Object> o = orderRow(oid);
        assertThat((BigDecimal) o.get("total")).isEqualByComparingTo("112.09");
        assertThat((BigDecimal) o.get("discount_amount")).isEqualByComparingTo("11.90");        // gross
        assertThat((BigDecimal) o.get("platform_discount_amount")).isEqualByComparingTo("10.00"); // net
        assertThat((BigDecimal) o.get("brand_discount_amount")).isEqualByComparingTo("0.00");
        assertThat((String) o.get("status")).isEqualTo("PENDING");

        Map<String, Object> oi = orderItemRows(oid).get(0);
        assertThat((BigDecimal) oi.get("item_discount_amount")).isEqualByComparingTo("10.00");    // net shares
        assertThat((BigDecimal) oi.get("platform_discount_share")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) oi.get("brand_discount_share")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) oi.get("brand_payout_amount")).isEqualByComparingTo("97.58");     // cash to brand
        assertThat((BigDecimal) oi.get("platform_fee_amount")).isEqualByComparingTo("9.52");      // commissionGross
        assertThat((BigDecimal) oi.get("commission_net")).isEqualByComparingTo("8.00");
        assertThat((BigDecimal) oi.get("commission_vat")).isEqualByComparingTo("1.52");

        assertThat(usedCount("TESTADMIN10")).isEqualTo(1); // reserved at placement

        BigDecimal payAmount = jdbc.queryForObject("SELECT amount FROM payments WHERE order_id = ?", BigDecimal.class, oid);
        assertThat(payAmount).isEqualByComparingTo("112.09"); // = order total (product 107.10 + shipping 4.99)

        BigDecimal baseline = brandPending(a.brand().getId());

        confirmPaid(oid);

        assertThat((String) orderRow(oid).get("status")).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, oid)).isEqualTo("PAID");
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("102.57"); // 97.58 product payout + 4.99 shipping
        BigDecimal ledgerFee = jdbc.queryForObject(
                "SELECT platform_fee FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                BigDecimal.class, oid);
        assertThat(ledgerFee).isEqualByComparingTo("8.00"); // platform_fee = commissionNet
        assertThat(usedCount("TESTADMIN10")).isEqualTo(1); // no further increment

        asAdmin(admin, () -> orderService.updateOrderStatus(oid, OrderStatus.CANCELLED));
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo(baseline); // back to baseline
    }

    // ---- 2. Brand discount applies only to issuing brand's items in a mixed cart ----
    @Test
    void brandDiscountMixedCart() {
        User admin = seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "119.00", 50); // net 100
        long listingB = seedListing(b.brand(), b.user(), "59.50", 50);  // net 50
        String custToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        ResponseEntity<Map> created = createBrandDiscount(brandAToken, Map.of("code", "BRAND15", "percent", 0.15));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("type")).isEqualTo("BRAND"); // type forced by endpoint

        ResponseEntity<Map> order = postOrder(custToken, "BRAND15", List.of(item(listingA, 1), item(listingB, 1)));
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        long oid = orderId(order);

        // BRAND15 applies only to A (net 100 → net discount 15.00, split 7.50/7.50). A's customer
        // gross = 101.15; B untouched at 59.50. Product subtotal 160.65; + shipping 4.99 per
        // distinct brand (2 brands here, both GLOBAL_DEFAULT — no BrandShippingProfile seeded) =
        // 9.98 -> order total 170.63. gross reduction 17.85 (shipping is never discounted).
        Map<String, Object> o = orderRow(oid);
        assertThat((BigDecimal) o.get("total")).isEqualByComparingTo("170.63");
        assertThat((BigDecimal) o.get("discount_amount")).isEqualByComparingTo("17.85");        // gross
        assertThat((BigDecimal) o.get("platform_discount_amount")).isEqualByComparingTo("7.50"); // net
        assertThat((BigDecimal) o.get("brand_discount_amount")).isEqualByComparingTo("7.50");

        Map<String, Object> rowA = orderItemForBrand(oid, a.brand().getId());
        assertThat((BigDecimal) rowA.get("item_discount_amount")).isEqualByComparingTo("15.00");
        assertThat((BigDecimal) rowA.get("platform_discount_share")).isEqualByComparingTo("7.50");
        assertThat((BigDecimal) rowA.get("brand_discount_share")).isEqualByComparingTo("7.50");
        assertThat((BigDecimal) rowA.get("brand_payout_amount")).isEqualByComparingTo("88.65");
        assertThat((BigDecimal) rowA.get("platform_fee_amount")).isEqualByComparingTo("12.50"); // commissionGross

        Map<String, Object> rowB = orderItemForBrand(oid, b.brand().getId());
        assertThat((BigDecimal) rowB.get("item_discount_amount")).isEqualByComparingTo("0.00");
        assertThat((BigDecimal) rowB.get("brand_payout_amount")).isEqualByComparingTo("48.79");
        assertThat((BigDecimal) rowB.get("platform_fee_amount")).isEqualByComparingTo("10.71"); // commissionGross

        BigDecimal baseA = brandPending(a.brand().getId());
        BigDecimal baseB = brandPending(b.brand().getId());

        confirmPaid(oid);
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("93.64"); // 88.65 product payout + 4.99 shipping
        assertThat(brandPending(b.brand().getId())).isEqualByComparingTo("53.78"); // 48.79 product payout + 4.99 shipping

        asAdmin(admin, () -> orderService.updateOrderStatus(oid, OrderStatus.CANCELLED));
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo(baseA);
        assertThat(brandPending(b.brand().getId())).isEqualByComparingTo(baseB);
    }

    // ---- 3. Exhaustion (single-use) + expiry are rejected (409); no order created ----
    @Test
    void discountExhaustionAndExpiryRejection() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "100.00", 50);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        Map<String, Object> single = new HashMap<>();
        single.put("code", "ONCE");
        single.put("percent", 0.10);
        single.put("maxUses", 1);
        assertThat(createAdminDiscount(adminToken, single).getStatusCode().value()).isEqualTo(201);

        assertThat(postOrder(custToken, "ONCE", List.of(item(listing, 1))).getStatusCode().value()).isEqualTo(201);
        assertThat(usedCount("ONCE")).isEqualTo(1);

        ResponseEntity<Map> second = postOrder(custToken, "ONCE", List.of(item(listing, 1)));
        assertThat(second.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()); // 409
        assertThat(usedCount("ONCE")).isEqualTo(1);
        assertThat(ordersWithCode("ONCE")).isEqualTo(1); // only the first persisted

        Map<String, Object> expired = new HashMap<>();
        expired.put("code", "EXPIRED");
        expired.put("percent", 0.10);
        expired.put("validUntil", LocalDateTime.now().minusDays(1).toString());
        assertThat(createAdminDiscount(adminToken, expired).getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> expiredOrder = postOrder(custToken, "EXPIRED", List.of(item(listing, 1)));
        assertThat(expiredOrder.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()); // 409
        assertThat((String) expiredOrder.getBody().get("message")).containsIgnoringCase("expired");
    }

    // ---- 4. Brand code on a cart with none of that brand's products -> 400, no order ----
    @Test
    void brandDiscountWrongBrandRejection() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        seedListing(a.brand(), a.user(), "100.00", 50);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 50);
        String custToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        assertThat(createBrandDiscount(brandAToken, Map.of("code", "BRAND15", "percent", 0.15))
                .getStatusCode().value()).isEqualTo(201);

        ResponseEntity<Map> order = postOrder(custToken, "BRAND15", List.of(item(listingB, 1)));
        assertThat(order.getStatusCode().value()).isEqualTo(HttpStatus.BAD_REQUEST.value()); // 400
        assertThat((String) order.getBody().get("message")).containsIgnoringCase("does not apply");
        assertThat(totalOrders()).isZero();
    }

    // ---- 5. Admin discount exceeding a brand's low commission rate -> rejected (409), no order ----
    @Test
    void adminDiscountExceedsCommissionRateEdge() {
        seedAdmin();
        seedCustomer();
        BrandFixture low = seedBrand("BrandLow", "brand-low", "0.08"); // 8% commission
        long listing = seedListing(low.brand(), low.user(), "119.00", 50); // net 100, base commission 8.00
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        assertThat(createAdminDiscount(adminToken, Map.of("code", "MARGIN10", "percent", 0.10))
                .getStatusCode().value()).isEqualTo(201);

        // 10% of net 100 = 10.00 platform share, but base commission is only 8.00 → commissionNet < 0.
        ResponseEntity<Map> order = postOrder(custToken, "MARGIN10", List.of(item(listing, 1)));
        assertThat(order.getStatusCode().value()).isEqualTo(HttpStatus.CONFLICT.value()); // 409
        assertThat((String) order.getBody().get("message")).containsIgnoringCase("platform commission");
        assertThat(totalOrders()).isZero();
        assertThat(usedCount("MARGIN10")).isEqualTo(0); // never reserved
    }

    // ---- 6. Concurrent checkout with one single-use code: exactly one wins ----
    @Test
    void concurrentSingleUseCodeAtomicity() throws Exception {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "100.00", 5);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        Map<String, Object> single = new HashMap<>();
        single.put("code", "RACE");
        single.put("percent", 0.10);
        single.put("maxUses", 1);
        assertThat(createAdminDiscount(adminToken, single).getStatusCode().value()).isEqualTo(201);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> attempt = () -> {
            start.await();
            return postOrder(custToken, "RACE", List.of(item(listing, 1))).getStatusCode().value();
        };
        Future<Integer> f1 = pool.submit(attempt);
        Future<Integer> f2 = pool.submit(attempt);
        start.countDown();
        int s1 = f1.get();
        int s2 = f2.get();
        pool.shutdown();

        assertThat(List.of(s1, s2)).containsExactlyInAnyOrder(201, HttpStatus.CONFLICT.value());
        assertThat(usedCount("RACE")).isEqualTo(1);
        assertThat(ordersWithCode("RACE")).isEqualTo(1);
    }

    // ---- 7. Legacy order with NULL snapshot falls back to recompute in the ledger ----
    @Test
    void oldOrderCompatibilityFallback() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "100.00", 50);
        String custToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> order = postOrder(custToken, null, List.of(item(listing, 1)));
        assertThat(order.getStatusCode().value()).isEqualTo(201);
        long oid = orderId(order);

        // Simulate a pre-V5 order: wipe the per-item money snapshot. commission_net = NULL is the
        // trigger that routes the ledger to the legacy gross recompute (lineTotal × rate).
        jdbc.update("UPDATE order_items SET platform_fee_amount = NULL, brand_payout_amount = NULL, " +
                "commission_net = NULL, commission_rate = NULL WHERE order_id = ?", oid);

        confirmPaid(oid); // recordOrderPayment must fall back to lineTotal x rate

        BigDecimal fee = jdbc.queryForObject(
                "SELECT platform_fee FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                BigDecimal.class, oid);
        BigDecimal payout = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                BigDecimal.class, oid);
        assertThat(fee).isEqualByComparingTo("18.00");
        assertThat(payout).isEqualByComparingTo("82.00");
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99"); // 82.00 product payout + 4.99 shipping
    }

    // ---- 8. Partial refund prorates the stored net payout ----
    @Test
    void partialRefundPreservesDiscountProportions() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 50); // net 100
        String custToken = login("customer@it.local", "Customer123!");
        String brandAToken = login("brand-a@it.local", "Brand123!");

        assertThat(createBrandDiscount(brandAToken, Map.of("code", "BRAND15", "percent", 0.15))
                .getStatusCode().value()).isEqualTo(201);

        long oid = orderId(postOrder(custToken, "BRAND15", List.of(item(listing, 1))));
        confirmPaid(oid);
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("93.64"); // 88.65 product payout + 4.99 shipping

        // Order total is now 101.15 (product) + 4.99 (this order's one brand, GLOBAL_DEFAULT
        // shipping) = 106.14 -> refunding €40.46 is 40.46/106.14 ≈ 38.1195% of the order total, not
        // the pre-shipping 40%. recordRefund(Order, BigDecimal, String) fractions the reversal
        // against order.getTotal(), so that fraction of the net payout (product + shipping) is reversed.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s ->
                ledgerService.recordRefund(orderRepository.findById(oid).orElseThrow(),
                        new BigDecimal("40.46"), "ref-partial"));

        // (88.65 + 4.99) - ((88.65 + 4.99) * 40.46/106.14) = 93.64 - 35.69 = 57.95
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("57.95");
    }

    // ===== helpers =====

    private void asAdmin(User admin, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.getAuthorities()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Map<String, Object> orderItemForBrand(long orderId, long brandId) {
        return jdbc.queryForMap(
                "SELECT oi.* FROM order_items oi " +
                "JOIN product_variants v ON v.id = oi.variant_id " +
                "JOIN products p ON p.id = v.product_id " +
                "WHERE oi.order_id = ? AND p.brand_id = ?", orderId, brandId);
    }

    private int ordersWithCode(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE discount_code = ?", Integer.class, code);
    }

    private int totalOrders() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class);
    }
}
