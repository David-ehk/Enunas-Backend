package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the return-approval email to the customer AFTER the approval transaction commits.
 * Best-effort: any SMTP failure is logged and swallowed — it must never propagate or roll back
 * an already-committed approval. The customer finds the return address in their in-app order
 * view as the reliable fallback; the email is the convenience layer on top.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReturnApprovedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReturnApproved(ReturnApprovedEvent event) {
        try {
            String brand = event.brandName() != null ? event.brandName() : "dem Verkäufer";
            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Rückgabe an " + brand + " für Bestellung " + event.orderNumber() + " genehmigt",
                    "Deine Rückgabe wurde genehmigt!\n\n" +
                    "Retourennummer: " + event.returnNumber() + "\n" +
                    "Marke: " + brand + "\n\n" +
                    "Bitte sende die Artikel dieser Marke an folgende Adresse:\n" +
                    event.returnShipToAddress() + "\n\n" +
                    "Vermerke bitte die Retourennummer (" + event.returnNumber() +
                    ") gut sichtbar auf dem Paket.\n\n" +
                    "Enthält deine Bestellung Artikel mehrerer Marken, erhältst du pro Marke eine " +
                    "eigene Retourennummer und Adresse — bitte sende die Pakete getrennt.\n\n" +
                    "Den aktuellen Status und alle Retourenadressen findest du jederzeit " +
                    "in deinem Konto unter Meine Bestellungen."
            );
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The approval is already persisted; only the customer notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=return-approved order={} return={} reason={}",
                    event.orderNumber(), event.returnNumber(), ex.getMessage());
        }
    }
}
