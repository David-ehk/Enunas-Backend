package com.enunas.backend.discount.integration;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 2 — two near-simultaneous paid webhooks for the same order must book exactly once. The
 * pessimistic lock in confirmPaymentByWebhook serializes the PENDING→PAID transition; a sequential
 * double-call would NOT prove this, so the two confirmations run on separate threads.
 */
class ConcurrentWebhookIdempotencyTest extends AbstractDiscountIntegrationTest {

    @Test
    void concurrentWebhooks_bookExactlyOnce() throws Exception {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 50); // gross 119 → net 100
        String custToken = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(custToken, null, List.of(item(listing, 1))));

        // Fire two identical confirmations from two threads, released together.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> attempt = () -> {
            start.await();
            orderService.confirmPaymentByWebhook(oid);
            return null;
        };
        Future<Void> f1 = pool.submit(attempt);
        Future<Void> f2 = pool.submit(attempt);
        start.countDown();
        f1.get();
        f2.get();
        pool.shutdown();

        // Exactly one ORDER_PAYMENT entry for the single line item.
        Integer paymentEntries = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'ORDER_PAYMENT'",
                Integer.class, oid);
        assertThat(paymentEntries).isEqualTo(1);

        // Brand credited exactly once: no-discount domestic payout on gross 119 = 97.58.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("97.58");
        assertThat((String) orderRow(oid).get("status")).isEqualTo("PAID");
        assertThat((BigDecimal) orderRow(oid).get("total")).isEqualByComparingTo("119.00");
    }
}
