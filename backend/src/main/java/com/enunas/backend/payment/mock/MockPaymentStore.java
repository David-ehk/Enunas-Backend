package com.enunas.backend.payment.mock;

import com.enunas.backend.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Slf4j
@Component
@Profile("mock-payments")
public class MockPaymentStore {

    private final ConcurrentHashMap<String, MockPayment> payments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> refundIdsByKey = new ConcurrentHashMap<>();

    /**
     * Atomically replays the refundId already stored for {@code key}, or mints and stores a new one
     * via {@code mint} when none exists yet. A separate get-then-put (a lookup call followed by a
     * store call) is a race: two genuinely concurrent callers can both see no existing key and each
     * mint their own id. {@link ConcurrentHashMap#computeIfAbsent} makes the check-and-set a single
     * atomic operation, so only one caller ever mints.
     */
    public String replayOrMint(String key, Supplier<String> mint) {
        return refundIdsByKey.computeIfAbsent(key, k -> mint.get());
    }

    /** Clears all mock provider state. Tests truncate the DB with RESTART IDENTITY, so without this
     *  an order id — and therefore an idempotency key — recurs in the next test and collides. */
    public void reset() {
        payments.clear();
        refundIdsByKey.clear();
    }

    public void save(MockPayment payment) {
        payments.put(payment.getId(), payment);
    }

    public MockPayment find(String id) {
        return payments.get(id);
    }

    public void markPaid(String id) {
        MockPayment p = payments.get(id);
        if (p != null) {
            p.setStatus(PaymentStatus.PAID);
        } else {
            log.warn("MockPaymentStore.markPaid: no payment for id={}", id);
        }
    }

    public void markRefunded(String id, String refundId) {
        MockPayment p = payments.get(id);
        if (p != null) {
            p.setStatus(PaymentStatus.REFUNDED);
            p.setRefundId(refundId);
        } else {
            log.warn("MockPaymentStore.markRefunded: no payment for id={}", id);
        }
    }
}
