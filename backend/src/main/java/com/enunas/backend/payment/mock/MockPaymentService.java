package com.enunas.backend.payment.mock;

import com.enunas.backend.payment.CreatePaymentCommand;
import com.enunas.backend.payment.PaymentDetails;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentResult;
import com.enunas.backend.payment.PaymentStatus;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.payment.RefundResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@Profile("mock-payments")
@RequiredArgsConstructor
public class MockPaymentService implements PaymentProvider {

    private final MockPaymentStore store;
    private final MockWebhookDispatcher webhookDispatcher;

    @Override
    public PaymentResult createPayment(CreatePaymentCommand command) {
        String id = "pay_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        store.save(new MockPayment(id, command.amount()));
        webhookDispatcher.dispatchPaymentPaid(id);

        log.info("MockPaymentService: created payment id={} amount={} {}", id, command.amount(), command.currency());
        return new PaymentResult(id, "http://localhost:3000/mock-checkout/" + id);
    }

    @Override
    public RefundResult refundPayment(RefundCommand command) {
        MockPayment payment = store.find(command.paymentId());
        if (payment == null) {
            throw new IllegalStateException("MockPaymentService: payment not found: " + command.paymentId());
        }

        // Atomic replay-or-mint: two genuinely concurrent callers with the same idempotency key must
        // never both mint a refund id. store.replayOrMint uses ConcurrentHashMap#computeIfAbsent, so
        // only ONE caller's supplier ever runs; `minted` tells us whether it was ours.
        boolean[] minted = {false};
        String key = command.idempotencyKey();
        String refundId = key == null
                ? "ref_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                : store.replayOrMint(key, () -> {
                    minted[0] = true;
                    return "ref_mock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                });

        if (key != null && !minted[0]) {
            log.info("MockPaymentService: replaying refund {} for idempotency key {}", refundId, key);
            return new RefundResult(refundId);
        }

        store.markRefunded(command.paymentId(), refundId);
        webhookDispatcher.dispatchRefundCreated(refundId, command.paymentId());

        log.info("MockPaymentService: refunded payment={} refundId={} amount={}",
                command.paymentId(), refundId, command.amount());
        return new RefundResult(refundId);
    }

    @Override
    public PaymentDetails getPaymentDetails(String paymentId) {
        MockPayment payment = store.find(paymentId);
        if (payment == null) {
            throw new IllegalStateException("MockPaymentService: payment not found: " + paymentId);
        }
        return new PaymentDetails(payment.getStatus() == PaymentStatus.PAID, payment.getAmount());
    }
}
