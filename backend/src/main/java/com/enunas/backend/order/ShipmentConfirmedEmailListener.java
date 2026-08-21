package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Notifies the buyer that their order shipped, AFTER the shipment-confirmation transaction
 * commits. Best-effort: an SMTP failure is logged and swallowed, same as {@link
 * OrderConfirmationEmailListener} — it must never undo an already-committed PAID → SHIPPED
 * transition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentConfirmedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentConfirmed(ShipmentConfirmedEvent event) {
        try {
            String body = "Deine Bestellung ist unterwegs!\nVersanddienstleister: " + event.carrier()
                    + "\nTracking-Nummer: " + event.trackingNumber()
                    + (event.note() != null && !event.note().isBlank() ? "\nHinweis: " + event.note() : "");

            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Deine Bestellung " + event.orderNumber() + " wurde versendet",
                    body);

            log.info("Shipment confirmation email sent to {} for order {}", event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The shipment is already confirmed; only the buyer notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=shipment-confirmed order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
