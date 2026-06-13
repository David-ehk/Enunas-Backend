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
            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Rückgabe für Bestellung " + event.orderNumber() + " genehmigt",
                    "Deine Rückgabe wurde genehmigt!\n\n" +
                    "Retourennummer: " + event.returnNumber() + "\n\n" +
                    "Bitte sende das Paket an folgende Adresse:\n" +
                    event.returnShipToAddress() + "\n\n" +
                    "Vermerke bitte die Retourennummer (" + event.returnNumber() +
                    ") gut sichtbar auf dem Paket.\n\n" +
                    "Den aktuellen Status und die Retourenadresse findest du jederzeit " +
                    "in deinem Konto unter Meine Bestellungen."
            );
        } catch (Exception ex) {
            log.error("Best-effort return-approval email failed for order {} / {} — approval already persisted: {}",
                    event.orderNumber(), event.returnNumber(), ex.getMessage());
        }
    }
}
