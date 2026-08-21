package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notifies the buyer that an admin cancelled their order, AFTER the cancellation transaction
 * commits. Best-effort: an SMTP failure is logged and swallowed — it must never undo an
 * already-committed CANCELLED transition or the discount-usage release that came with it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelledEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCancelled(OrderCancelledEvent event) {
        try {
            String body = "Deine Bestellung " + event.orderNumber() + " wurde storniert.\n"
                    + "Grund: " + event.reason()
                    + (event.note() != null && !event.note().isBlank() ? "\nHinweis: " + event.note() : "");

            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Bestellung " + event.orderNumber() + " storniert",
                    body);

            log.info("Order-cancellation email sent to {} for order {}", event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The cancellation is already persisted; only the buyer notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=order-cancelled order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
