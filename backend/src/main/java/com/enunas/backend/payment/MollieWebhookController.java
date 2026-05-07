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
    private final MolliePaymentService molliePaymentService;
    private final OrderService orderService;

    /**
     * Mollie posts id=<molliePaymentId> (form-encoded) when payment status changes.
     * We re-fetch from Mollie to verify status and amount before updating the order.
     * The endpoint is idempotent: duplicate webhook calls for an already-processed
     * payment are silently accepted (200) without side effects.
     */
    @PostMapping
    public ResponseEntity<Void> handleWebhook(@RequestParam String id) {
        // Trust only our own transactionId lookup — never accept an orderId from the request.
        Payment payment = paymentRepository.findByTransactionId(id).orElse(null);
        if (payment == null) {
            // Test ping from Mollie dashboard or unknown ID — accept silently.
            log.warn("Mollie webhook: no payment record for id {} — ignoring", id);
            return ResponseEntity.ok().build();
        }

        // Issue 1: short-circuit before hitting Mollie's API if already processed.
        if (payment.getStatus() == PaymentStatus.PAID) {
            log.info("Mollie webhook: payment {} already processed — no-op", id);
            return ResponseEntity.ok().build();
        }

        MolliePaymentService.MolliePaymentDetails details;
        try {
            details = molliePaymentService.getPayment(id);
        } catch (Exception e) {
            log.error("Mollie webhook: failed to fetch payment {} from Mollie: {}", id, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }

        // Issue 3: use isPaid boolean — single place where Mollie status string is interpreted.
        if (!details.isPaid()) {
            log.info("Mollie webhook: payment {} not yet paid — no action taken", id);
            return ResponseEntity.ok().build();
        }

        // CB-5: reject if confirmed amount doesn't match what we charged.
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
