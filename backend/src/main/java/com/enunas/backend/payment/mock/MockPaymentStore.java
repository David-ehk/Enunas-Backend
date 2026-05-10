package com.enunas.backend.payment.mock;

import com.enunas.backend.payment.PaymentStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Profile("mock-payments")
public class MockPaymentStore {

    private final ConcurrentHashMap<String, MockPayment> payments = new ConcurrentHashMap<>();

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
