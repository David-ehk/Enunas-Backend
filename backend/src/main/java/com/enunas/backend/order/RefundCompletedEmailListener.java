package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Confirms a completed refund to the customer. Before this, {@code processRefund} succeeding
 * produced only a log line — the customer was never told their money came back. Best-effort: an
 * SMTP failure is logged and swallowed, same as {@link ReturnApprovedEmailListener} — it must never
 * roll back an already-committed refund.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundCompletedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRefundCompleted(RefundCompletedEvent event) {
        try {
            String brand = event.brandName() != null ? event.brandName() : "dem Verkäufer";
            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Rückerstattung für Bestellung " + event.orderNumber() + " abgeschlossen",
                    "Deine Rückerstattung wurde bearbeitet!\n\n" +
                    "Retourennummer: " + event.returnNumber() + "\n" +
                    "Marke: " + brand + "\n" +
                    "Erstatteter Betrag: " + event.refundAmount() + " " + event.currency() + "\n\n" +
                    "Die Gutschrift erfolgt auf dein ursprüngliches Zahlungsmittel und kann je nach " +
                    "Anbieter einige Werktage in Anspruch nehmen.\n\n" +
                    "Den aktuellen Status deiner Bestellung findest du jederzeit in deinem Konto " +
                    "unter Meine Bestellungen."
            );
        } catch (Exception ex) {
            log.error("Best-effort refund-completed email failed for order {} / {} — refund already persisted: {}",
                    event.orderNumber(), event.returnNumber(), ex.getMessage());
        }
    }
}
