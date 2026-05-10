package com.enunas.backend.payment.mock;

import com.enunas.backend.order.OrderService;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Drop-in replacement for the Mollie webhook endpoint when running under the
 * mock-payments profile. Mirrors MollieWebhookController's behaviour exactly —
 * same idempotency guard, same delegation to OrderService — but without the
 * round-trip to Mollie's API.
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/mock")
@Profile("mock-payments")
@RequiredArgsConstructor
public class MockWebhookController {

    private final PaymentRepository paymentRepository;
    private final MockPaymentStore store;
    private final OrderService orderService;

    @PostMapping("/payment")
    public ResponseEntity<Void> paymentPaid(@RequestBody WebhookEvent event) {
        Payment payment = paymentRepository.findByTransactionId(event.getPaymentId()).orElse(null);
        if (payment == null) {
            log.warn("MockWebhookController: no payment for transactionId={} — ignoring", event.getPaymentId());
            return ResponseEntity.ok().build();
        }

        if (payment.getStatus() == PaymentStatus.PAID) {
            return ResponseEntity.ok().build(); // idempotent
        }

        store.markPaid(event.getPaymentId());

        try {
            orderService.confirmPaymentByWebhook(payment.getOrder().getId());
            log.info("MockWebhookController: order {} confirmed via mock payment {}",
                    payment.getOrder().getOrderNumber(), event.getPaymentId());
        } catch (Exception e) {
            log.error("MockWebhookController: confirmPaymentByWebhook failed for {}: {}",
                    event.getPaymentId(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/refund")
    public ResponseEntity<Void> refundCreated(@RequestBody WebhookEvent event) {
        // Refund DB state is already committed by RefundPersistenceHelper before this fires.
        // This endpoint exists for protocol parity — acknowledge and return.
        log.debug("MockWebhookController: refund.created paymentId={} refundId={}",
                event.getPaymentId(), event.getRefundId());
        return ResponseEntity.ok().build();
    }
}
