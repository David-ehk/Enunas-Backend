package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tells the buyer their payment arrived after the order was cancelled and that the money will be
 * refunded, AFTER the transaction recording the payment commits. Best-effort: an SMTP failure is
 * logged and swallowed — the REFUND_REQUIRED flag is the source of truth, not this mail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LatePaymentRefundRequiredEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLatePayment(LatePaymentRefundRequiredEvent event) {
        try {
            String body = "Deine Zahlung über " + event.amount() + " " + event.currency()
                    + " für die Bestellung " + event.orderNumber() + " ist bei uns eingegangen.\n"
                    + "Leider konnten wir die Bestellung nicht ausführen. " + event.reason() + "\n\n"
                    + "Wir erstatten dir den vollen Betrag auf dein ursprüngliches Zahlungsmittel. "
                    + "Du musst dafür nichts weiter tun.";

            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Zahlung für Bestellung " + event.orderNumber() + " wird erstattet",
                    body);

            log.info("Late-payment refund email sent to {} for order {}", event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert, same as the other order mails.
            log.error("EMAIL_DELIVERY_FAILURE type=late-payment-refund order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
