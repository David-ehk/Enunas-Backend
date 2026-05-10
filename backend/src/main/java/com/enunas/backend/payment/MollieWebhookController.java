package com.enunas.backend.payment;

import com.enunas.backend.order.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/webhooks/mollie")
@RequiredArgsConstructor
public class MollieWebhookController {

    private final PaymentRepository paymentRepository;
    private final PaymentProvider paymentProvider;
    private final OrderService orderService;

    /**
     * Mollie posts id=<molliePaymentId> (form-encoded) when payment status changes.
     * We re-fetch from the payment provider to verify status and amount before updating the order.
     * Idempotent: duplicate calls for an already-processed payment are silently accepted.
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(@RequestParam String id) {
        Payment payment = paymentRepository.findByTransactionId(id).orElse(null);
        if (payment == null) {
            log.warn("Mollie webhook: no payment record for id={} — ignoring", id);
            return ResponseEntity.ok().build();
        }

        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("Mollie webhook: payment {} already processed — no-op", id);
            return ResponseEntity.ok().build();
        }

        PaymentDetails details;
        try {
            details = paymentProvider.getPaymentDetails(id);
        } catch (Exception e) {
            log.error("Mollie webhook: failed to fetch payment {} from provider: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        if (!details.isPaid()) {
            log.info("Mollie webhook: payment {} not yet paid — no action taken", id);
            return ResponseEntity.ok().build();
        }

        if (payment.getAmount().compareTo(details.amount()) != 0) {
            log.error("Mollie webhook: AMOUNT MISMATCH for payment {} — expected {} {}, got {} {}",
                    id, payment.getAmount(), payment.getCurrency(),
                    details.amount(), payment.getCurrency());
            return ResponseEntity.badRequest().build();
        }

        try {
            orderService.confirmPaymentByWebhook(payment.getOrder().getId());
            log.info("Mollie webhook: order {} marked PAID via payment {}",
                    payment.getOrder().getOrderNumber(), id);
        } catch (Exception e) {
            log.error("Mollie webhook: failed to confirm payment {} for order {}: {}",
                    id, payment.getOrder().getOrderNumber(), e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        return ResponseEntity.ok().build();
    }
}
